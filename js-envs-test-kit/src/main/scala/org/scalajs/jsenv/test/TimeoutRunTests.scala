package org.scalajs.jsenv.test

import org.scalajs.jsenv.JSEnv

import org.junit.{Before, Test}
import org.junit.Assert._
import org.junit.Assume._

import scala.concurrent.duration._

private[test] class TimeoutRunTests(config: JSEnvSuiteConfig, withCom: Boolean) {
  private val kit = new TestKit(config, withCom)
  import kit._

  @Before
  def before: Unit = {
    assumeTrue("JSEnv needs timeout support", config.supportsTimeout)
  }

  /** Slack for timeout tests (see #3457)
   *
   *  Empirically we can observe that timing can be off by ~0.1ms. By cutting
   *  10ms slack, we definitely account for this without compromising the tests.
   */
  private val slack = 10.millis

  @Test
  def basicTimeoutTest: Unit = {

    val deadline = (300.millis - slack).fromNow

    """
    setTimeout(function() { console.log("1"); }, 200);
    setTimeout(function() { console.log("2"); }, 100);
    setTimeout(function() { console.log("3"); }, 300);
    setTimeout(function() { console.log("4"); },   0);
    """ hasOutput
    """|4
       |2
       |1
       |3
       |""".stripMargin

    assertTrue("Execution took too little time", deadline.isOverdue())

  }

  @Test
  def intervalTest: Unit = {
    val deadline = (100.millis - slack).fromNow

    // We rely on the test kit to terminate the test after 5 iterations.
    """
    setInterval(function() { console.log("tick"); }, 20);
    """ hasOutput
    """|tick
       |tick
       |tick
       |tick
       |tick
       |""".stripMargin

     assertTrue("Execution took too little time", deadline.isOverdue())

  }
}
