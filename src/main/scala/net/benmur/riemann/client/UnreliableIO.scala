package net.benmur.riemann.client

import java.net.{ DatagramPacket, DatagramSocket, SocketAddress }
import java.util.concurrent.atomic.AtomicLong
import akka.actor.{ Actor, ActorSystem, Props }
import akka.util.Timeout
import scala.collection.mutable.WrappedArray

trait UnreliableIO {
  private val nClients = new AtomicLong(0L) // FIXME this should be more global

  class UnreliableConnection(where: SocketAddress, factory: Unreliable#SocketFactory, dispatcherId: Option[String] = None)(implicit system: ActorSystem) extends Connection[Unreliable] {
    val props = {
      val p = Props(new UnconnectedConnectionActor(where, factory))
      if (dispatcherId.isEmpty) p else p.withDispatcher(dispatcherId.get)
    }
    val ioActor = system.actorOf(props, "riemann-udp-client-" + nClients.incrementAndGet)
  }

  implicit object UnreliableSendOff extends SendOff[Unreliable] {
    def sendOff(connection: Connection[Unreliable], command: Write): Unit = connection match {
      case uc: UnreliableConnection => uc.ioActor tell command
      case c                        => System.err.println("don't know how to send data to " + c.getClass.getName)
    }
  }

  private[this] class UnconnectedConnectionActor(where: SocketAddress, factory: Unreliable#SocketFactory) extends Actor {
    val connection = factory(where)
    def receive = {
      case Write(msg) => connection send msg.toByteArray
    }
  }

  val makeUdpConnection: Unreliable#SocketFactory = (addr) => {
    val dest = new DatagramSocket(addr)
    new UnconnectedSocketWrapper {
      override def send(data: WrappedArray[Byte]) = dest send new DatagramPacket(data.array, data.length)
    }
  }

  implicit object OneWayConnectionBuilder extends ConnectionBuilder[Unreliable] {
    implicit def buildConnection(where: SocketAddress, factory: Option[Unreliable#SocketFactory], dispatcherId: Option[String])(implicit system: ActorSystem, timeout: Timeout): Connection[Unreliable] =
      new UnreliableConnection(where, factory getOrElse makeUdpConnection, dispatcherId)
  }
}

object UnreliableIO extends UnreliableIO