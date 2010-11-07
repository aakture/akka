package akka.stm

import akka.config.Config

import org.multiverse.api.{Transaction => MultiverseTransaction}
import org.multiverse.commitbarriers.CountDownCommitBarrier
import org.multiverse.templates.TransactionalCallable

/**
 * Coordinated transactions across actors.
 */
object Coordinated {
  val DefaultFactory = TransactionFactory(DefaultTransactionConfig, "DefaultCoordinatedTransaction")
  val Fair = Config.config.getBool("akka.stm.fair", true)

  def apply(message: Any = null) = new Coordinated(message, createBarrier)

  def unapply(c: Coordinated): Option[Any] = Some(c.message)

  def createBarrier = new CountDownCommitBarrier(1, Fair)
}

/**
 * Coordinated transactions across actors.
 * <p/>
 * Coordinated is a wrapper for any message that adds a CountDownCommitBarrier to
 * coordinate transactions across actors or threads. To start a new coordinated transaction
 * that you will also participate in, use:
 * <p/>
 * <pre>
 * val coordinated = Coordinated()
 * </pre>
 * <p/>
 * Creating a Coordinated object will create a count down barrier with one member. For each
 * member in the coordination set a coordinated transaction is expected to be created.
 * <p/>
 * To start a coordinated transaction in another actor that you won't participate in yourself
 * can send the Coordinated message directly and the recipient is the first member of the
 * coordinating actors:
 * <p/>
 * <pre>
 * actor ! Coordinated(Message)
 * </pre>
 * <p/>
 * To receive a coordinated message in an actor:
 * <p/>
 * <pre>
 * def receive = {
 *   case coordinated @ Coordinated(Message) => ...
 * }
 * </pre>
 * <p/>
 * To include another actor in the same coordinated transaction set that you've created or
 * received, use the apply method on that object. This will increment the number of parties
 * involved by one.
 * <p/>
 * <pre>
 * actor ! coordinated(Message)
 * </pre>
 * <p/>
 * To enter a coordinated transaction use the atomic method of the Coordinated object:
 * <p/>
 * <pre>
 * coordinated atomic {
 *   // Do something in transaction that will wait for the other transactions before committing.
 *   // If any of the coordinated transactions fail then they all fail.
 * }
 * </pre>
 * <p/>
 */
class Coordinated(val message: Any, barrier: CountDownCommitBarrier) {
  def apply(msg: Any) = {
    barrier.incParties(1)
    new Coordinated(msg, barrier)
  }

  def atomic[T](body: => T)(implicit factory: TransactionFactory = Coordinated.DefaultFactory): T =
    atomic(factory)(body)

  def atomic[T](factory: TransactionFactory)(body: => T): T = {
    factory.boilerplate.execute(new TransactionalCallable[T]() {
      def call(mtx: MultiverseTransaction): T = {
        factory.addHooks
        val result = body
        val timeout = factory.config.timeout
        try {
          barrier.tryJoinCommit(mtx, timeout.length, timeout.unit)
        } catch {
          // Need to catch IllegalStateException until we have fix in Multiverse, since it throws it by mistake
          case e: IllegalStateException => ()
        }
        result
      }
    })
  }
}