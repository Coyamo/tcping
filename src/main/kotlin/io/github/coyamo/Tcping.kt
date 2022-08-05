package io.github.coyamo

import io.github.coyamo.Tcping.printStatistics
import java.net.InetAddress
import java.net.Socket
import java.text.SimpleDateFormat

fun main(args: Array<String>) {
    val tcpStats = Stats().apply {
        hostname = "www.baidu.com"
        port = 443
        ip = Tcping.resolveHostname(this)
        startTime = Tcping.getSystemTime()

        if (hostname == ip?.hostName) {
            isIP = true
        }

        if (retryHostnameResolveAfter > 0 && !isIP) {
            shouldRetryResolve = true
        }
    }

    Thread {
        var i = 0
        while (i++ < 4) {
            if (tcpStats.shouldRetryResolve) {
                Tcping.retryResolve(tcpStats)
            }
            Tcping.tcping(tcpStats)
        }
        tcpStats.printStatistics()

    }.start()
}

data class Stats(
    var startTime: Long = 0,
    var endTime: Long = 0,
    var startOfUptime: Long = 0,
    var startOfDowntime: Long = 0,
    var lastSuccessfulProbe: Long = 0,
    var lastUnsuccessfulProbe: Long = 0,
    var retryHostnameResolveAfter: Int = 0,
    var ip: InetAddress? = null,
    var port: Int = 0,
    var hostname: String = "",
    var rtt: MutableList<Long> = mutableListOf(),
    var totalUnsuccessfulPkts: Int = 0,
    var longestDowntime: LongestTime = LongestTime(),
    var totalSuccessfulPkts: Int = 0,
    var totalUptime: Long = 0,
    var ongoingUnsuccessfulPkts: Int = 0,
    var retriedHostnameResolves: Int = 0,
    var longestUptime: LongestTime = LongestTime(),
    var totalDowntime: Long = 0,
    var wasDown: Boolean = false, // Used to determine the duration of a downtime
    var isIP: Boolean = false, // If IP is provided instead of hostname, suppresses printing the IP information twice
    var shouldRetryResolve: Boolean = false,
)


data class LongestTime(
    var start: Long = 0,
    var end: Long = 0,
    var duration: Float = 0f
)


data class RttResults(
    var min: Long = 0,
    var max: Long = 0,
    var average: Float = 0f,
    var hasResults: Boolean = false,
)

data class ReplyMsg(
    val msg: String,
    val rtt: Long
)

object Tcping {
    private const val noReply = "No reply"
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private val hourFormatter = SimpleDateFormat("HH:mm:ss")


    /* Print the last successful and unsuccessful probes */
    private fun Stats.printLastSucUnsucProbes() {
        val formattedLastSuccessfulProbe = formatter.format(lastSuccessfulProbe)
        val formattedLastUnsuccessfulProbe = formatter.format(lastUnsuccessfulProbe)

        print("last successful probe:   ")
        if (lastSuccessfulProbe == 0L) {
            println("Never succeeded")
        } else {
            println(formattedLastSuccessfulProbe)
        }

        print("last unsuccessful probe: ")
        if (lastUnsuccessfulProbe == 0L) {
            println("Never failed")
        } else {
            println(formattedLastUnsuccessfulProbe)
        }
    }

    /* Print the longest uptime */
    private fun Stats.printLongestUptime() {
        if (longestUptime.duration == 0F) {
            return
        }

        val uptime = calcTime(longestUptime.duration.toInt().toLong())

        print("longest consecutive uptime:   ")
        print(uptime)
        print(" from ")
        print(formatter.format(longestUptime.start))
        print(" to ")
        println(formatter.format(longestUptime.end))
    }

    /* Print the start and end time of the program */
    fun Stats.printDurationStats() {
        val duration: Long
        val durationDiff: Long

        println("--------------------------------------")
        println("TCPing started at: ${formatter.format(startTime)}")

        /* If the program was not terminated, no need to show the end time */
        if (endTime == 0L) {
            durationDiff = getSystemTime() - startTime
        } else {
            println("TCPing ended at:   ${formatter.format(endTime)}")
            durationDiff = endTime - startTime
        }
        duration = durationDiff - 8 * 60 * 60 * 1000
        println("duration (HH:MM:SS): ${hourFormatter.format(duration)}")
        println()
    }

    /* Print the longest downtime */
    private fun Stats.printLongestDowntime() {
        if (longestDowntime.duration == 0f) {
            return
        }

        val downtime = calcTime(longestDowntime.duration.toInt().toLong())

        print("longest consecutive downtime: ")
        print(downtime)
        print(" from ")
        print(formatter.format(longestDowntime.start))
        print(" to ")
        println(formatter.format(longestDowntime.end))
    }

    /* Print the number of times that we tried resolving a hostname after a failure */
    private fun Stats.printRetryResolveStats() {
        print("retried to resolve hostname ")
        print(retriedHostnameResolves)
        println(" times")
    }

    /* Print statistics when program exits */
    fun Stats.printStatistics() {
        endTime = getSystemTime()
        val rttResults = findMinAvgMaxRttTime(rtt)

        if (!rttResults.hasResults) {
            return
        }

        val totalPackets = totalSuccessfulPkts + totalUnsuccessfulPkts
        val totalUptime = calcTime(totalUptime)
        val totalDowntime = calcTime(totalDowntime)
        val packetLoss = totalUnsuccessfulPkts.toFloat() / totalPackets * 100

        /* general stats */
        println()
        println("--- $hostname TCPing statistics ---")
        print("$totalPackets probes transmitted, ")
        print("$totalSuccessfulPkts received, ")

        /* packet loss stats */
        if (packetLoss == 0F) {
            print(String.format("%.2f%%", packetLoss))
        } else if (packetLoss > 0 && packetLoss <= 30) {
            print(String.format("%.2f%%!!", packetLoss))
        } else {
            print(String.format("%.2f%%!!!", packetLoss))
        }

        println(" packet loss")

        /* successful packet stats */
        print("successful probes:   ")
        println(totalSuccessfulPkts)

        /* unsuccessful packet stats */
        print("unsuccessful probes: ")
        println(totalUnsuccessfulPkts)

        printLastSucUnsucProbes()

        /* uptime and downtime stats */
        print("total uptime: ")
        println("  $totalUptime")
        print("total downtime: ")
        println(totalDowntime)

        /* calculate the last longest time */
        if (!wasDown) {
            calcLongestUptime(this, lastSuccessfulProbe)
        } else {
            calcLongestDowntime(this, lastUnsuccessfulProbe)
        }

        /* longest uptime stats */
        printLongestUptime()

        /* longest downtime stats */
        printLongestDowntime()

        /* resolve retry stats */
        if (isIP) {
            printRetryResolveStats()
        }

        /* latency stats.*/
        println("rtt min/avg/max: ${rttResults.min}/${String.format("%.2f", rttResults.average)}/${rttResults.max} ms")

        /* duration stats */
        printDurationStats()
    }

    /* Print the total downtime */
    private fun Stats.printTotalDownTime() {
        val latestDowntimeDuration = (getSystemTime() - this.startOfDowntime) / 1000
        val calculatedDowntime = calcTime(latestDowntimeDuration)
        println("No response received for $calculatedDowntime")
    }

    /* Print TCP probe replies according to our policies */
    private fun Stats.printReply(replyMsg: ReplyMsg) {
        if (isIP) {
            if (replyMsg.msg == noReply) {
                println("${replyMsg.msg} from $ip on port $port TCP_conn=$totalUnsuccessfulPkts")
            } else {
                println("${replyMsg.msg} from $ip on port $port TCP_conn=$totalSuccessfulPkts time=${replyMsg.rtt} ms")
            }
        } else {
            if (replyMsg.msg == noReply) {
                println("${replyMsg.msg} from $hostname ($ip) on port $port TCP_conn=$totalUnsuccessfulPkts")
            } else {
                println("${replyMsg.msg} from $hostname ($ip) on port $port TCP_conn=$totalUnsuccessfulPkts time=${replyMsg.rtt} ms")
            }
        }
    }

    fun resolveHostname(stats: Stats): InetAddress {
        return InetAddress.getByName(stats.hostname)
    }

    private fun Stats.printRetryingToResolve() {
        println("retrying to resolve $hostname")
    }

    /* Retry resolve hostname after certain number of failures */
    fun retryResolve(stats: Stats) {
        if (stats.ongoingUnsuccessfulPkts > stats.retryHostnameResolveAfter) {
            stats.printRetryingToResolve()
            stats.ip = resolveHostname(stats)
            stats.ongoingUnsuccessfulPkts = 0
            stats.retriedHostnameResolves += 1
        }
    }

    /* Create LongestTime structure */
    private fun newLongestTime(startTime: Long, endTime: Long): LongestTime {
        return LongestTime(startTime, endTime, (endTime - startTime) / 1000f)
    }


    /* Find min/avg/max RTT values. The last int acts as err code */
    private fun findMinAvgMaxRttTime(timeArr: MutableList<Long>): RttResults {
        val arrLen = timeArr.size
        var accum = 0L
        val rttResults = RttResults()
        for (i in 0 until arrLen) {
            if (i == 0) {
                rttResults.min = timeArr[i]
            }
            accum += timeArr[i]
            if (timeArr[i] > rttResults.max) {
                rttResults.max = timeArr[i]
            }
            if (timeArr[i] < rttResults.min) {
                rttResults.min = timeArr[i]
            }
        }
        if (arrLen > 0) {
            rttResults.hasResults = true
            rttResults.average = accum.toFloat() / arrLen
        }
        return rttResults
    }

    /* Calculate cumulative time */
    private fun calcTime(time: Long): String {
        val timeStr: String

        val hours = time / (60 * 60)
        val timeMod = time % (60 * 60)
        val minutes = timeMod / (60)
        val seconds = timeMod % (60)

        /* Calculate hours */
        if (hours >= 2) {
            timeStr = "$hours hours $minutes minutes $seconds seconds"
            return timeStr
        } else if (hours == 1L && minutes == 0L && seconds == 0L) {
            timeStr = "$hours hour"
            return timeStr
        } else if (hours == 1L) {
            timeStr = "$hours hour $minutes minutes $seconds seconds"
            return timeStr
        }

        /* Calculate minutes */
        if (minutes >= 2) {
            timeStr = "$minutes minutes $seconds seconds"
            return timeStr
        } else if (minutes == 1L && seconds == 0L) {
            timeStr = "$minutes minute"
            return timeStr
        } else if (minutes == 1L) {
            timeStr = "$minutes minute $seconds seconds"
            return timeStr
        }

        /* Calculate seconds */
        return if (seconds >= 2) {
            timeStr = "$seconds seconds"
            timeStr
        } else {
            timeStr = "$seconds second"
            timeStr
        }
    }

    /* Calculate the longest uptime */
    private fun calcLongestUptime(tcpStats: Stats, endOfUptime: Long) {
        if (tcpStats.startOfUptime == 0L || endOfUptime == 0L) {
            return
        }

        val longestUptime = newLongestTime(tcpStats.startOfUptime, endOfUptime)

        if (tcpStats.longestUptime.end == 0L) {
            /* It means it is the first time we're calling this function */
            tcpStats.longestUptime = longestUptime
        } else if (longestUptime.duration >= tcpStats.longestUptime.duration) {
            tcpStats.longestUptime = longestUptime
        }
    }

    /* Calculate the longest downtime */
    private fun calcLongestDowntime(tcpStats: Stats, endOfDowntime: Long) {
        if (tcpStats.startOfDowntime == 0L || endOfDowntime == 0L) {
            return
        }

        val longestDowntime = newLongestTime(tcpStats.startOfDowntime, endOfDowntime)

        if (tcpStats.longestDowntime.end == 0L) {
            /* It means it is the first time we're calling this function */
            tcpStats.longestDowntime = longestDowntime
        } else if (longestDowntime.duration >= tcpStats.longestDowntime.duration) {
            tcpStats.longestDowntime = longestDowntime
        }
    }

    /* Get current system time */
    fun getSystemTime() = System.currentTimeMillis()

    /* Ping host, TCP style */
    fun tcping(tcpStats: Stats) {
        val connStart = getSystemTime()
        var connEnd = connStart
        var now = connStart
        try {
            val socket = Socket(tcpStats.ip, tcpStats.port)
            connEnd = getSystemTime() - connStart

            val rtt = connEnd
            now = getSystemTime()

            socket.use {
                /* if the previous probe failed
            and the current one succeeded: */
                if (tcpStats.wasDown) {
                    /* calculate the total downtime since
                    the previous successful probe */
                    tcpStats.printTotalDownTime()

                    /* Update startOfUptime */
                    tcpStats.startOfUptime = now

                    /* Calculate the longest downtime */
                    calcLongestDowntime(tcpStats, now)
                    tcpStats.startOfDowntime = getSystemTime()

                    tcpStats.wasDown = false
                    tcpStats.ongoingUnsuccessfulPkts = 0
                }

                /* It means it is the first time to get a response*/
                if (tcpStats.startOfUptime == 0L) {
                    tcpStats.startOfUptime = now
                }

                tcpStats.totalUptime += 1
                tcpStats.totalSuccessfulPkts += 1
                tcpStats.lastSuccessfulProbe = now

                tcpStats.rtt.add(rtt)
                tcpStats.printReply(ReplyMsg("Reply", rtt))
            }
        } catch (e: Exception) {
            /* if the previous probe was successful
          and the current one failed: */
            if (!tcpStats.wasDown) {
                /* Update startOfDowntime */
                tcpStats.startOfDowntime = now

                /* Calculate the longest uptime */
                calcLongestUptime(tcpStats, now)
                tcpStats.startOfUptime = getSystemTime()

                tcpStats.wasDown = true
            }

            tcpStats.totalDowntime += 1
            tcpStats.totalUnsuccessfulPkts += 1
            tcpStats.lastUnsuccessfulProbe = now
            tcpStats.ongoingUnsuccessfulPkts += 1

            tcpStats.printReply(ReplyMsg("No reply", 0))
        }

        try {
            Thread.sleep(1000 - connEnd)
        } catch (e: Exception) {
        }

    }

}

