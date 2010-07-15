/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.camel

import CamelMessageConversion.toExchangeAdapter

import org.apache.camel._
import org.apache.camel.processor.SendProcessor

import se.scalablesolutions.akka.actor.{Actor, ActorRef}

/**
 * Mixed in by Actor implementations that produce messages to Camel endpoints.
 *
 * @author Martin Krasser
 */
trait Producer { this: Actor =>

  /**
   * Message headers to copy by default from request message to response-message.
   */
  private val headersToCopyDefault = Set(Message.MessageExchangeId)

  /**
   * <code>Endpoint</code> object resolved from current CamelContext with
   * <code>endpointUri</code>.
   */
  private lazy val endpoint = CamelContextManager.context.getEndpoint(endpointUri)

  /**
   * <code>SendProcessor</code> for producing messages to <code>endpoint</code>.
   */
  private lazy val processor = createSendProcessor

  /**
   * If set to false (default), this producer expects a response message from the Camel endpoint.
   * If set to true, this producer communicates with the Camel endpoint with an in-only message
   * exchange pattern (fire and forget).
   */
  def oneway: Boolean = false

  /**
   * Returns the Camel endpoint URI to produce messages to.
   */
  def endpointUri: String

  /**
   * Returns the names of message headers to copy from a request message to a response message.
   * By default only the Message.MessageExchangeId is copied. Applications may override this to
   * define an application-specific set of message headers to copy.
   */
  def headersToCopy: Set[String] = headersToCopyDefault

  /**
   * Default implementation of <code>Actor.shutdown</code> for freeing resources needed
   * to actually send messages to <code>endpointUri</code>.
   */
  override def shutdown {
    processor.stop
  }

  /**
   * Creates an in-only message exchange from <code>msg</code> and sends it to the endpoint
   * specified by <code>endpointUri</code>. No reply is made to the original sender.
   *
   * @param msg message to produce
   */
  protected def produceOneway(msg: Any): Unit = {
    val exchange = createInOnlyExchange.fromRequestMessage(Message.canonicalize(msg))
    processor.process(exchange, new AsyncCallback {
      def done(doneSync: Boolean): Unit = { /* ignore because it's an in-only exchange */ }
    })
  }

  /**
   * Creates an in-out message exchange from <code>msg</code> and sends it to the endpoint
   * specified by <code>endpointUri</code>. The out-message returned by the endpoint is
   * returned to the original sender.
   *
   * @param msg message to produce
   */
  protected def produceTwoway(msg: Any): Unit = {
    val cmsg = Message.canonicalize(msg)
    val exchange = createInOutExchange.fromRequestMessage(cmsg)
    processor.process(exchange, new AsyncCallback {
      // Need copies of sender and senderFuture references here
      // since the callback could be done later by another thread.
      val sender = self.sender
      val senderFuture = self.senderFuture

      def done(doneSync: Boolean): Unit = {
        val response =
          if (exchange.isFailed) exchange.toFailureMessage(cmsg.headers(headersToCopy))
          else exchange.toResponseMessage(cmsg.headers(headersToCopy))

        if (senderFuture.isDefined) senderFuture.get completeWithResult response
        else if (sender.isDefined) sender.get ! response
        else log.warning("No destination for sending response")
      }
    })
  }

  /**
   * Partial function that matches any argument and sends it as message to the endpoint
   */
  protected def produce: Receive = {
    case msg => if (oneway) produceOneway(msg) else produceTwoway(msg)
  }

  /**
   * Default implementation of Actor.receive
   */
  protected def receive = produce

  /**
   * Creates a new in-only Exchange from the endpoint specified by <code>endpointUri</code>.
   */
  protected def createInOnlyExchange: Exchange = createExchange(ExchangePattern.InOnly)

  /**
   * Creates a new in-out Exchange from the endpoint specified by <code>endpointUri</code>.
   */
  protected def createInOutExchange: Exchange = createExchange(ExchangePattern.InOut)

  /**
   * Creates a new Exchange with given <code>pattern</code> from the endpoint specified by
   * <code>endpointUri</code>.
   */
  protected def createExchange(pattern: ExchangePattern): Exchange = endpoint.createExchange(pattern)

  /**
   * Creates a new <code>SendProcessor</code> for <code>endpoint</code>.
   */
  private def createSendProcessor = {
    val sendProcessor = new SendProcessor(endpoint)
    sendProcessor.start
    sendProcessor
  }
}

/**
 * A one-way producer.
 *
 * @author Martin Krasser
 */
trait Oneway extends Producer { this: Actor =>
  override def oneway = true
}

