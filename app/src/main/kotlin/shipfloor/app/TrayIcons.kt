package shipfloor.app

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/** Draws the Shipfloor mark for the tray and the window icon; waiting adds an amber badge. */
object TrayIcons {
    val SIZES = listOf(22, 24, 32, 48, 64)

    private val TILE = Color(0x0C0C0B)
    private val TAPE = Color(0xF2C230)
    private val CARD = Color(0xF3F2EC)
    private val BADGE = Color(0xFF5A36)

    /** The app icon: the Shipfloor mark on its dark tile (docs/img/icon.svg, a 64 by 64 drawing). */
    fun renderTile(size: Int): BufferedImage {
        val unit = size / 64.0
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            quality(g)
            g.color = TILE
            g.fill(RoundRectangle2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), 28 * unit, 28 * unit))
            drawMark(g, unit, 0.0, 0.0, 8.0)
        } finally {
            g.dispose()
        }
        return image
    }

    /**
     * The tray icon: the same mark with no tile, scaled to fill the square so it stays readable at 22 px, on a
     * transparent ground. With [waiting] the card turns red-orange: the owner is wanted.
     */
    fun render(size: Int, waiting: Boolean): BufferedImage {
        // The mark spans about 48.5 by 44.5 units; scale it to the square and centre it.
        val unit = size / 49.0
        val dx = -7.2 * unit
        val dy = -8 * unit + (size - 45 * unit) / 2
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            quality(g)
            drawMark(g, unit, dx, dy, 9.0, card = if (waiting) BADGE else CARD)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun quality(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    /** The floor tape and the card, in the 64 by 64 space of the icon, moved by ([dx], [dy]) pixels. */
    private fun drawMark(g: Graphics2D, unit: Double, dx: Double, dy: Double, tape: Double, card: Color = CARD) {
        g.color = TAPE
        g.stroke = BasicStroke((tape * unit).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val route = Path2D.Double()
        route.moveTo(dx + 12 * unit, dy + 48 * unit)
        route.lineTo(dx + 44 * unit, dy + 48 * unit)
        route.lineTo(dx + 44 * unit, dy + 32 * unit)
        route.lineTo(dx + 20 * unit, dy + 32 * unit)
        route.lineTo(dx + 20 * unit, dy + 16 * unit)
        route.lineTo(dx + 40 * unit, dy + 16 * unit)
        g.draw(route)
        g.color = card
        g.fill(RoundRectangle2D.Double(dx + 40 * unit, dy + 8 * unit, 16 * unit, 16 * unit, 6 * unit, 6 * unit))
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
