package shipfloor.app

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/** Draws the Shipfloor mark for the tray and the window icon; waiting adds an amber badge. */
object TrayIcons {
    val SIZES = listOf(22, 24, 48)

    private val TILE = Color(0x0C0C0B)
    private val TAPE = Color(0xF2C230)
    private val CARD = Color(0xF3F2EC)
    private val BADGE = Color(0xF5A623)

    /**
     * The Shipfloor mark: floor tape that takes a card from the Inbox to Done, on a dark tile (docs/img/icon.svg, a
     * 64 by 64 drawing). With [waiting] an amber badge says the owner is wanted.
     */
    fun render(size: Int, waiting: Boolean): BufferedImage {
        val unit = size / 64.0
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = TILE
            g.fill(RoundRectangle2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), 28 * unit, 28 * unit))

            g.color = TAPE
            g.stroke = BasicStroke((8 * unit).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val route = Path2D.Double()
            route.moveTo(12 * unit, 48 * unit)
            route.lineTo(44 * unit, 48 * unit)
            route.lineTo(44 * unit, 32 * unit)
            route.lineTo(20 * unit, 32 * unit)
            route.lineTo(20 * unit, 16 * unit)
            route.lineTo(40 * unit, 16 * unit)
            g.draw(route)

            g.color = CARD
            g.fill(RoundRectangle2D.Double(40 * unit, 8 * unit, 16 * unit, 16 * unit, 6 * unit, 6 * unit))

            if (waiting) {
                val ring = 10 * unit
                val dot = 8 * unit
                g.color = TILE
                g.fill(Ellipse2D.Double(52 * unit - ring, 52 * unit - ring, 2 * ring, 2 * ring))
                g.color = BADGE
                g.fill(Ellipse2D.Double(52 * unit - dot, 52 * unit - dot, 2 * dot, 2 * dot))
            }
        } finally {
            g.dispose()
        }
        return image
    }

    /** The image as the StatusNotifierItem `IconPixmap` wants it: ARGB, network byte order. */
    fun pixmap(size: Int, waiting: Boolean): IconPixmap {
        val image = render(size, waiting)
        val bytes = ByteArray(size * size * 4)
        var at = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val argb = image.getRGB(x, y)
                bytes[at++] = (argb ushr 24).toByte()
                bytes[at++] = (argb ushr 16).toByte()
                bytes[at++] = (argb ushr 8).toByte()
                bytes[at++] = argb.toByte()
            }
        }
        return IconPixmap(size, size, bytes)
    }
}
