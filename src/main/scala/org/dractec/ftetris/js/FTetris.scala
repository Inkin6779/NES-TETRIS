package org.dractec
package ftetris.js

import scalajs.js.annotation._
import org.scalajs.dom._

import scala.scalajs.js
import scala.scalajs.js.timers._
import org.dractec.ftetris.logic.Tiles._
import org.dractec.ftetris.logic.Game._
import org.scalajs.dom
import cats.implicits._
import cats.effect._
import org.scalajs.dom.raw.HTMLImageElement

import scala.collection.mutable

@JSExportTopLevel("FTetris")
object FTetris {

  @js.native
  trait GameConf extends js.Object {
    val canv: html.Canvas = js.native
    val onpointchange: js.Function1[Int, Unit] = js.native
    val onlevelchange: js.Function1[Int, Unit] = js.native
    val onlineclear: js.Function1[Int, Unit] = js.native
    val touchRootNode: dom.Node = js.native
    val ongameend: js.Function0[Unit] = js.native
    val onpausestart: js.Function0[Unit] = js.native
    val onpauseend: js.Function0[Unit] = js.native
    val simpleRenderingMode: js.Function0[Boolean] = js.native
  }

  var canStart: Boolean = true
  var paused: Boolean = false

  /* // TODO: guideline colors
        Cyan I
        Yellow O
        Purple T
        Green S
        Red Z
        Blue J
        Orange L
  */

  val tileColors = Map[Option[Tile], String](
    Straight.some -> "#4AC948",
    Box.some      -> "#83F52C",
    LeftL.some    -> "#76EE00",
    RightL.some   -> "#66CD00",
    Tee.some      -> "#49E20E",
    SnakeL.some   -> "#83F52C",
    SnakeR.some   -> "#4DBD33",
    // for simple rendering mode
    None          -> "#303030"//"black"//"#2F4F4F"//"#696969",
  )

  implicit class DOMListWrapper[A](val v: DOMList[A]) extends AnyVal {
    def toList = (for (i <- 0 to v.length) yield v(i)).toList
  }

  @JSExport
  def startGame(gc: GameConf): Unit = {

    if (!canStart) return
    canStart = false

    var lastRenderingMode = false
    def simpleRenderingMode = {
      val res = if (js.isUndefined(gc.simpleRenderingMode)) false else gc.simpleRenderingMode()
      lastRenderingMode = res
      res
    }

    val touchRoot: dom.Node = if (js.isUndefined(gc.touchRootNode)) gc.canv else gc.touchRootNode

    type Ctx2D =
      CanvasRenderingContext2D
    val ctx = gc.canv.getContext("2d")
      .asInstanceOf[Ctx2D]

    def callIfDef(f: js.Function0[Unit]): Unit = if (!js.isUndefined(f)) f()

    var keysDown = Set[Int]()
    var lastTouchMove: Move = Nothing
    val validInput = Set(37, 65, 39, 68, 40, 83, 32, 27, 80)

    val gs = initGS(Config(
      input = IO { new Input{
        override def leftDown = keysDown.contains(37) || keysDown.contains(65) || lastTouchMove == LeftM

        override def rightDown = keysDown.contains(39) || keysDown.contains(68) || lastTouchMove == RightM

        override def softDropDown = keysDown.contains(40) || keysDown.contains(83) || lastTouchMove == Drop

        override def rotateDown = keysDown.contains(32) || lastTouchMove == Rotate
    }}))

    var lastState = gs
    var lastField: Option[GameField] = None

  // __________ DRAWING FUNCTIONS __________

    def redraw(oldField: Option[GameField], newField: GameField): Unit = {
      val lastMode = lastRenderingMode
      if (simpleRenderingMode) {
        if (oldField.isEmpty || !lastMode) {
          // draw all first time
          newField.foreach{case (c, t) => drawTile(t, c, lastRenderingMode)}
        } else {
          // draw only the difference
          val diff = newField.collect{
            case (c, t) if t != oldField.get.apply(c) => c -> t}
          diff.foreach{case (c, t) => drawTile(t, c, lastRenderingMode)}
        }
      } else {
        drawGradient()
        newField.foreach{case (c, t) => if (t.isDefined) drawTile(t, c, lastRenderingMode) }
      }
    }

    def gradientStyle: CanvasGradient = {
      val grd = ctx.createLinearGradient(0, 0, 0, gc.canv.height)
      grd.addColorStop(0, "black")
      grd.addColorStop(1, "grey")
      grd
    }

    def drawGradient(): Unit = {
      ctx.fillStyle = gradientStyle
      ctx.fillRect(0, 0, gc.canv.width, gc.canv.height)
      drawSneakyGrid()
    }

    def clearAll(): Unit = {
      ctx.clearRect(0, 0, gc.canv.width, gc.canv.height)
    }

    def drawSneakyGrid(): Unit = {
      val widthPerTile = gc.canv.width / gs.conf.boardDims.x
      val heightPerTile = gc.canv.height / gs.conf.boardDims.y
      ctx.strokeStyle = "black"
      ctx.lineWidth = 1
      ctx.globalAlpha = 0.1
      for (x <- 0 until gs.conf.boardDims.x) {
        ctx.strokeRect(x * widthPerTile, 0, 1, gc.canv.height)
      }
      for (y <- 0 until gs.conf.boardDims.y) {
        ctx.strokeRect(0, y * heightPerTile, gc.canv.width, 1)
      }
      ctx.globalAlpha = 1
    }

    def drawTile(tile: Option[Tile], coord: Coord, renderingMode: Boolean): Unit = {
      val widthPerTile = gc.canv.width / gs.conf.boardDims.x
      val heightPerTile = gc.canv.height / gs.conf.boardDims.y
      ctx.fillStyle = tileColors(tile)
      ctx.fillRect(coord.x * widthPerTile, coord.y * heightPerTile, widthPerTile, heightPerTile)
      if (renderingMode) {
        ctx.globalAlpha = 1
        ctx.strokeStyle = "#696969" //"#2F4F4F"
        ctx.lineWidth = 1
      } else {
        ctx.globalAlpha = 0.1
        ctx.strokeStyle = gradientStyle
        ctx.lineWidth = 2
      }
      ctx.strokeRect(coord.x * widthPerTile, coord.y * heightPerTile, widthPerTile, heightPerTile)
      ctx.globalAlpha = 1
    }

  // __________ PAUSE HANDLING __________

    def pause(): Unit = {
      drawGradient()
      val center = Coord(gc.canv.width / 2, gc.canv.height / 2)
      val linewidth = gc.canv.width / 10
      val lineheight = gc.canv.height / 5
      ctx.fillStyle = "red"
      ctx.fillRect(center.x - linewidth * 1.5, center.y - lineheight / 2, linewidth, lineheight)
      ctx.fillRect(center.x + linewidth * 0.5, center.y - lineheight / 2, linewidth, lineheight)
    } andFinally callIfDef(gc.onpausestart)

    def resume(): Unit = {
      drawGradient()
      val field = globalTetCoverage(lastState)
        .map(tc => lastState.field |+| tc)
        .getOrElse(lastState.field)
      redraw(None, field)
    } andFinally callIfDef(gc.onpauseend)

  // __________ KEYBOARD EVENTS __________

    dom.window.addEventListener("keydown", (e: dom.KeyboardEvent) => {
      if (validInput(e.keyCode)) {
        e.preventDefault()
        e.stopPropagation()
        if (e.keyCode == 27 || e.keyCode == 80) {
          paused = !paused
          if (paused) pause()
          else resume()
        }
        else keysDown += e.keyCode
      }}, useCapture = false)

    dom.window.addEventListener("keyup", (e: dom.KeyboardEvent) => {
      if (validInput(e.keyCode)) {
        e.preventDefault()
        e.stopPropagation()
        keysDown -= e.keyCode
      }}, useCapture = false)

  // __________ TOUCH EVENTS __________

    // idea: swipe to the left to move left
    // soft drop while swiping down
    // tap to rotate

    def pos(e: TouchEvent, i: Int = 0) =
      if (i >= e.touches.length) None
      else Some(e.touches(i)) map { t: Touch => {
        val cr = gc.canv.getBoundingClientRect()
        Coord(
          (t.clientX - cr.left).toInt,
          (t.clientY - cr.top).toInt
        )
      }}

    val movesSinceTouchStart = mutable.Stack[TouchEvent]()
    var moveIsDrop: Option[Boolean] = false.some
    var lastPause = 0l

    def handleTouchEnd(e: TouchEvent): Unit = {
      e.preventDefault()
      if (moveIsDrop.isEmpty || movesSinceTouchStart.lengthCompare(1) == 0)
        lastTouchMove = Rotate
      else lastTouchMove = Nothing
      moveIsDrop = None
    }
    touchRoot.addEventListener("touchstart", (e: TouchEvent) => {
      e.preventDefault()
      movesSinceTouchStart.clear()
      movesSinceTouchStart.push(e)
    })
    touchRoot.addEventListener("touchmove", (e: TouchEvent) => {
      // TODO: Rework control flow. Right now it's a mess.
      e.preventDefault()
      def currTime = System.currentTimeMillis()
      val thresh = gc.canv.width / gs.conf.boardDims.x / 30
      val last = movesSinceTouchStart.top
      movesSinceTouchStart.push(e)
      val cp = pos(e).get
      val lp = pos(last).get
      val cp1opt = pos(e, 1)
      val lp1opt = pos(last, 1)
      //noinspection UnitInMap
      cp1opt.flatMap(cp1 => lp1opt.map(lp1 =>
        if ((cp.y - lp.y) > thresh && (cp1.y - lp1.y) > thresh && (currTime - lastPause) > 500) {
          lastPause = currTime
          paused = !paused
          if (paused) pause()
          else resume()
        }
      )).getOrElse{
        if (moveIsDrop.getOrElse(true)) {
          if ((cp.y - lp.y) > (lp.x - cp.x).abs && (cp.y - lp.y) > thresh) { // soft drop
            lastTouchMove = Drop
            moveIsDrop = true.some
          }
          else if (moveIsDrop.isEmpty && (cp.x - lp.x).abs > thresh)
            moveIsDrop = false.some
          else lastTouchMove = Nothing
        }
        if (moveIsDrop.contains(false)) {
          if (cp.x - lp.x > thresh) lastTouchMove = RightM
          else if (lp.x - cp.x > thresh) lastTouchMove = LeftM
          else lastTouchMove = Nothing
        }
      }
    })
    touchRoot.addEventListener("touchend", handleTouchEnd)
    touchRoot.addEventListener("touchcancel", handleTouchEnd)

  // __________ GAME END STUFF __________

    var mainLoop: Option[SetIntervalHandle] = None

    val img = dom.document.createElement("img").asInstanceOf[HTMLImageElement]
    img.src = "img/gameover.png"

    def endGame(): Unit = {
      mainLoop.foreach(clearInterval)
      ctx.drawImage(img, 0, gc.canv.height/2 - img.height/2)
    }

    // __________ MAIN GAME LOOP __________

    clearAll()
    drawGradient()
    gc.onpointchange(0)
    gc.onlevelchange(gs.level)

    canStart = true
    callIfDef(gc.ongameend)

    var frameIsRunning = false

    mainLoop = setInterval(1000d/60d) {
      if (frameIsRunning) sys.error("Calculating a frame took too long.")
      frameIsRunning = true
      if (!paused) {
        // have to run here, since running everything at once fails to draw
        val (newState, isOver) = nextFrame(lastState).unsafeRunSync()

        if (lastState.points != newState.points) gc.onpointchange(newState.points)
        if (lastState.lastClears.count(_.clearTime >= 20) != newState.lastClears.count(_.clearTime >= 20))
          gc.onlineclear(newState.lastClears.size)
        if (lastState.level != newState.level) gc.onlevelchange(newState.level)

        if (lastTouchMove == Rotate && (newState.lastMoveTimes(Rotate) - newState.frameCount) < gs.conf.rotateDelay)
          lastTouchMove = Nothing

        val rawNewField = globalTetCoverage(newState)
          .map(tc => newState.field |+| tc)
        val newField = coverageWithClearAnimation(newState, rawNewField.getOrElse(newState.field))
        if (lastField.isEmpty || newField != lastField.get) {
          redraw(lastField, newField)
          lastField = newField.some
        }
        lastState = newState

        if (isOver) endGame()
      }
      frameIsRunning = false
    }.some
  }
}
