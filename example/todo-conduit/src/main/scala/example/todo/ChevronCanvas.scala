package example.todo

import ascent.*

/** Animated synthwave chevron via the typed Canvas helper. */
object ChevronCanvas:

  private val accent = "255, 0, 170"

  def apply(width: Int = 56, height: Int = 28): UI[Any] =
    Canvas.animated(width, height) { (ctx, t) =>
      val phase = (t % 1400.0) / 1400.0
      val s     = math.sin(phase * 2 * math.Pi)
      val dx    = s * 4.0
      val pulse = 0.55 + 0.45 * ((s + 1) / 2.0)

      val w = width.toDouble
      val h = height.toDouble

      ctx.clearRect(0, 0, w, h)

      val track = ctx.createLinearGradient(0, h / 2.0, w, h / 2.0)
      track.addColorStop(0.0, "rgba(255, 0, 170, 0.0)")
      track.addColorStop(0.5, "rgba(255, 0, 170, 0.22)")
      track.addColorStop(1.0, "rgba(255, 0, 170, 0.0)")
      ctx.fillStyle = track
      ctx.fillRect(2, h / 2.0 - 0.75, w - 4, 1.5)

      val cx  = w / 2.0 + dx
      val cy  = h / 2.0
      val arm = math.min(w, h) * 0.32

      def chevronPath(): Unit =
        ctx.beginPath()
        ctx.moveTo(cx - arm * 0.7, cy - arm)
        ctx.lineTo(cx + arm * 0.5, cy)
        ctx.lineTo(cx - arm * 0.7, cy + arm)

      ctx.lineCap = "round"
      ctx.lineJoin = "round"

      ctx.strokeStyle = s"rgba($accent, ${0.30 * pulse})"
      ctx.lineWidth = 7.0
      chevronPath(); ctx.stroke()

      ctx.strokeStyle = s"rgba($accent, ${0.55 * pulse})"
      ctx.lineWidth = 4.0
      chevronPath(); ctx.stroke()

      ctx.strokeStyle = s"rgba(255, 230, 250, ${0.85 * pulse + 0.15})"
      ctx.lineWidth = 2.0
      chevronPath(); ctx.stroke()
    }
end ChevronCanvas
