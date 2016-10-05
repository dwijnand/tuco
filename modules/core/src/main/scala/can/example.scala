package can

import can.free._
import can.free.{ connection => FC }
import can.hi.{ connection => HC }

import net.bmjames.opts._

import scalaz._, Scalaz.{ some => _, _ }, scalaz.effect._


class Example extends SafeShell {
  import Session.L

  val add = Info[Int](
    "add", "Adds a number to the current count.",
    intArgument(metavar("<number>"), help("Number to add.")).map { n => s =>
      for {
        _ <- HC.writeLn(s"${s.state} + $n = ${s.state + n}")
      } yield s.copy(state = s.state + n)
    }
  )

  def initialize: State[Session[Int], Unit] =
    for {
      _ <- L.commands := Builtins[Int] |+| Commands(add)
      _ <- L.prompt   := "tuco> "
    } yield ()

  val initialState: Session[Int] =
    initialize.exec(Session.initial(42))

  val shellMain: ConnectionIO[Unit] =
    HC.writeLn("Herro.")   *>
    CommandShell.run(initialState) *>
    HC.writeLn("Bye!")

}

///////////////


object  CommandShell {

  val Bang = "!(.*)".r

  val eventNotFound =
    HC.writeLn(s"history: event not found")

  def run[A](s: Session[A]): FC.ConnectionIO[Unit] = {
    def cmd(c: String) = s.commands.interp(c)(s).flatMap(s => run(c :: s))
    HC.readLn(s.prompt, s.history.toZipper).flatMap {
      case Bang(c) => s.history.recall(c).fold(eventNotFound)(cmd)
      case c       => cmd(c)
    } .unlessM(s.done)
  }

}

trait SafeShell extends net.wimpi.telnetd.shell.Shell {

  def shellMain: ConnectionIO[Unit]

  def run(c: net.wimpi.telnetd.net.Connection): Unit =
    shellMain.transK[IO].run(c).unsafePerformIO

  import net.wimpi.telnetd.net.ConnectionEvent
  final def connectionIdle(ce: ConnectionEvent): Unit = ()
  final def connectionLogoutRequest(ce: ConnectionEvent): Unit = ()
  final def connectionSentBreak(ce: ConnectionEvent): Unit = ()
  final def connectionTimedOut(ce: ConnectionEvent): Unit = ()
}

object Main {
  def main(args: Array[String]): Unit =
    net.wimpi.telnetd.TelnetD.main(Array("file:config.properties"))
}


object Builtins {

  import net.bmjames.opts. { Parser => _, _}
  import net.bmjames.opts.types._

  def exitP[A] = Info[A](
    ":exit", "Exit the shell.",
    Parser.pure(s => s.copy(done = true).point[FC.ConnectionIO])
  )

  def historyP[A] = Info[A](
    ":history", "Show command history.",
    Parser.pure(s => history(s.history).as(s))
  )

  def helpP[A] = Info[A](
    ":help", "Show command help.",
    Parser.pure { s =>
      val infos = s.commands.toList.sortBy(_.name)
      val w = infos.map(_.name.length).max + 3 // TODO: use kiama/PP
      infos.traverseU(i => HC.writeLn(i.name.padTo(w, ' ') + i.desc)).as(s)
    }
  )

  def apply[A] = Commands(exitP[A], historyP[A], helpP[A])

  def history(h: History): FC.ConnectionIO[Unit] =
    h.toList.reverse.zipWithIndex.traverseU { case (s, n) => HC.writeLn(s"$n: $s") } .void


}