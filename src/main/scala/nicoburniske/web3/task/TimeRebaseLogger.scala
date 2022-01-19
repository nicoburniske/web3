package nicoburniske.web3.task

import java.time.{Instant, LocalDateTime, LocalTime, ZoneId}

import monix.eval.Task
import monix.reactive.Observable
import nicoburniske.web3.Resources.{backend, scheduler}
import nicoburniske.web3.csv.CsvLogger
import nicoburniske.web3.eth.JsonRPC
import nicoburniske.web3.exchange.{CEX, DEX}
import nicoburniske.web3.utils.BetterLogger

import scala.concurrent.duration.{FiniteDuration, _}

object TimeRebaseLogger extends BetterLogger {
  def schedule(walletAddress: String, csvPath: String, runAtStart: Boolean): Task[Unit] = {
    val baseTask =
      if (runAtStart)
        loggingTask(walletAddress, csvPath)
      else
        Task.unit
    for {
      _ <- logTask("Scheduling rebase logger")
      _ <- baseTask
      _ <- scheduleRebaseLogEvent(walletAddress, csvPath)
    } yield ()
  }

  def scheduleRebaseLogEvent(walletAddress: String, csvPath: String): Task[Unit] = {
    Observable.intervalAtFixedRate(findTimeUntilNextRebase(), 8.hours).foreachL { _ =>
      loggingTask(walletAddress, csvPath).runAsyncAndForget
    }
  }

  def loggingTask(walletAddress: String, csvPath: String): Task[Unit] = {
    for {
      responses <- Task.parZip4(
        CEX.getPrices().send(backend),
        DEX.priceWMEMO().send(backend),
        DEX.priceTIME().send(backend),
        JsonRPC.getWalletTimeBalance(walletAddress))

      (prices, wMemo, time, balance) = responses
    } yield (prices.body, wMemo.body, time.body, balance) match {
      case (Right(prices), Right(Some((wmemoPool, priceWMemo))), Right(Some((timePool, priceTime))), Right(timeBalance)) =>
        logger.info("Log process starting")
        val newPrices = prices :+ (wmemoPool -> priceWMemo.toDouble) :+ (timePool -> priceTime.toDouble)
        CsvLogger.addLog(csvPath, timeBalance, newPrices)
        logger.info(s"Log successfully written to $csvPath")
      case (p, w, t, b)                                                                                                  =>
        logFailures("Failed to retrieve row data")(p, w, t, b)
    }
  }

  val UTC          = ZoneId.of("UTC")
  val REBASE_TIMES = Seq(6, 14, 22).map(LocalTime.of(_, 0))

  /**
   * Finds first rebase time. Rebases happen @ 06:00, 14:00, 22:00 UTC.
   *
   * @return
   *   duration until next rebase time + 1 min for contract to execute.
   */
  def findTimeUntilNextRebase(): FiniteDuration = {
    def findFirstRebaseTime(): Long = {
      val now        = LocalDateTime.now(UTC)
      val nowDate    = now.toLocalDate
      val todayTimes = REBASE_TIMES.map { time => LocalDateTime.of(nowDate, time) }
      val tmrwTimes  = REBASE_TIMES.map { time => LocalDateTime.of(nowDate.plusDays(1), time) }
      (todayTimes ++ tmrwTimes).find(dateTime => dateTime.isAfter(now)).get.atZone(UTC).toInstant.toEpochMilli
    }

    val rebaseTime = findFirstRebaseTime()
    val now        = Instant.now().toEpochMilli
    val oneMin     = 1.minute.toMillis
    (rebaseTime + oneMin - now).millis
  }
}
