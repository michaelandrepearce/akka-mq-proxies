package com.github.sstone.amqp.proxy

import akka.actor.{ActorLogging, Actor, ActorRef}
import akka.serialization.Serializer
import com.github.sstone.amqp.{Amqp, RpcClient, RpcServer}
import com.github.sstone.amqp.RpcServer.ProcessResult
import com.rabbitmq.client.AMQP.BasicProperties
import com.github.sstone.amqp.Amqp.{Publish, Delivery}
import concurrent.{ExecutionContext, Future, Await}
import akka.util.Timeout
import akka.pattern.ask
import scala.concurrent.duration._
import serializers.ProtobufSerializer
import util.Success

//import serializers.JsonSerializer
import com.rabbitmq.client.AMQP
import org.slf4j.LoggerFactory


object AmqpProxy {

  /**
   * "Generic" failure
   * @param error error code
   * @param reason error message
   */
  case class Failure(error: Int, reason: String)

  /**
   * serialize a message and return a (blob, AMQP properties) tuple. The following convention is used for the AMQP properties
   * the message will be sent with:
   * <ul>
   *   <li>contentEncoding is set to the name of the serializer that is used</li>
   *   <li>contentType is set to the name of the message class</li>
   * </ul>
   * @param msg input message
   * @param serializer serializer
   * @param deliveryMode AMQP delivery mode that will be included in the returned AMQP properties
   * @return a (blob, properties) tuple where blob is the serialized message and properties the AMQP properties the message
   *         should be sent with.
   */
  def serialize(msg: AnyRef, serializer: Serializer, deliveryMode: Int = 1) = {
    val body = serializer.toBinary(msg)
    val props = new BasicProperties.Builder().contentEncoding(Serializers.serializerToName(serializer)).contentType(msg.getClass.getName).deliveryMode(deliveryMode).build
    (body, props)
  }

  /**
   * deserialize a message
   * @param body serialized message
   * @param props AMQP properties, which contain meta-data for the serialized message
   * @return a deserialized message
   * @see [[com.github.sstone.amqp.proxy.AmqpProxy.serialize()]]
   */
  def deserialize(body: Array[Byte], props: AMQP.BasicProperties) = {
    Serializers.nameToSerializer(props.getContentEncoding).fromBinary(body,  Some(Class.forName(props.getContentType)))
  }

  class ProxyServer(server: ActorRef, timeout: Timeout = 30 seconds) extends RpcServer.IProcessor {
    import ExecutionContext.Implicits.global
    lazy val logger = LoggerFactory.getLogger(classOf[ProxyServer])

    def process(delivery: Delivery) = {
      logger.trace("consumer %s received %s with properties %s".format(delivery.consumerTag, delivery.envelope, delivery.properties))
      val request = deserialize(delivery.body, delivery.properties)
      logger.debug("handling delivery of type %s".format(request.getClass.getName))
      (server ? request)(timeout).mapTo[AnyRef].map {
        response => {
          logger.debug("sending response of type %s".format(response.getClass.getName))
          val (body, props) = serialize(response, Serializers.nameToSerializer(delivery.properties.getContentEncoding))
          ProcessResult(Some(body), Some(props)) // we answer with the same encoding type
        }
      }
    }

    def onFailure(delivery: Delivery, e: Throwable) = {
      val (body, props) = serialize(Failure(1, e.toString), ProtobufSerializer)
      ProcessResult(Some(body), Some(props))
    }
  }

  /**
   * standard  one-request/one response proxy, which allows to write (myActor ? MyRequest).mapTo[MyResponse]
   * @param client AMQP RPC Client
   * @param exchange exchange to which requests will be sent
   * @param routingKey routing key with which requests will be sent
   * @param serializer message serializer
   * @param timeout response time-out
   * @param mandatory AMQP mandatory flag used to sent requests with; default to true
   * @param immediate AMQP immediate flag used to sent requests with; default to false; use with caution !!
   * @param deliveryMode AMQP delivery mode to sent request with; defaults to 1 (
   */
  class ProxyClient(client: ActorRef, exchange: String, routingKey: String, serializer: Serializer, timeout: Timeout = 30 seconds, mandatory: Boolean = true, immediate: Boolean = false, deliveryMode: Int = 1) extends Actor {
    import ExecutionContext.Implicits.global

    def receive = {
      case msg: AnyRef => {
        // serialize the message
        val (body, props) = serialize(msg, serializer, deliveryMode = deliveryMode)

        // publish the serialized message (and tell the RPC client that we expect one response)
        val publish = Publish(exchange, routingKey, body, Some(props), mandatory = mandatory, immediate = immediate)
        val future = (client ? RpcClient.Request(publish :: Nil, 1))(timeout).mapTo[RpcClient.Response]
        val dest = sender

        // when the response comes back, deserialize it and send the deserialized message to the original sender
        future.onComplete {
          case Success(result) => {
            val delivery = result.deliveries(0)
            val response = deserialize(delivery.body, delivery.properties)
            // is the response is "Failure" (our own Failure type, not Scala/Akka's) turn it into an Akka failure
            // this could also have been done in deserialized but is more explicit here
            response match {
              case Failure(error, reason) => dest ! akka.actor.Status.Failure(new RuntimeException("error:%d reason:%s" format(error, reason)))
              case other => dest ! other
            }
          }
          case util.Failure(error) => dest ! akka.actor.Status.Failure(error)
        }
      }
    }
  }

  /**
   * "fire-and-forget" proxy, which allows to write myActor ! MyRequest
   * @param client AMQP RPC Client
   * @param exchange exchange to which requests will be sent
   * @param routingKey routing key with which requests will be sent
   * @param serializer message serializer
   * @param mandatory AMQP mandatory flag used to sent requests with; default to true
   * @param immediate AMQP immediate flag used to sent requests with; default to false; use with caution !!
   * @param deliveryMode AMQP delivery mode to sent request with; defaults to 1
   */
  class ProxySender(client: ActorRef, exchange: String, routingKey: String, serializer: Serializer, mandatory: Boolean = true, immediate: Boolean = false, deliveryMode: Int = 1) extends Actor with ActorLogging {

    def receive = {
      case Amqp.Ok(request, _) => log.debug("successfully processed request %s".format(request))
      case Amqp.Error(request, error) => log.error("error while processing %s : %s".format(request, error))
      case msg: AnyRef => {
        val (body, props) = serialize(msg, serializer, deliveryMode = deliveryMode)
        val publish = Publish(exchange, routingKey, body, Some(props), mandatory = mandatory, immediate = immediate)
        log.debug("sending %s to %s".format(publish, client))
        client ! publish
      }
    }
  }
}