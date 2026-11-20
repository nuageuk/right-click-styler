package com.rightclickstyler;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collections;
import org.junit.Test;
import net.runelite.client.ui.FontManager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Compares the actual header pixels with a wide, untruncated render. */
public class MenuHeaderGeometryTest
{
	private static final RightClickStylerConfig CONFIG = new RightClickStylerConfig() {};
	private static final Color HEADER_TEXT = Color.WHITE;
	private static final Color TAG_TEXT = new Color(0xFF9040);

	@Test
	public void capturedSawMenuUsesItsFullNativeHeaderBudget()
	{
		Theme theme = theme(FontChoice.RS_BOLD, 96);
		assertFullHeader(theme, "Choose Option", 103, 887, 990, "Configure");
	}

	@Test
	public void runePouchAndWindMoteHeadersRenderFullyWhenNativeRectangleCanHoldThem()
	{
		for (FontChoice font : new FontChoice[]{FontChoice.RS_BOLD, FontChoice.SANS})
		{
			for (int opacity : new int[]{0, 100})
			{
				Theme theme = theme(font, opacity);
				for (String header : new String[]{"Rune pouch", "Captured wind mote"})
				{
					int width = measuredHeaderWidth(theme, header) + 8;
					assertFullHeader(theme, header, width, 10, width + 20, "Configure");
					assertFullHeader(theme, "<col=ff9040>" + header + "</col>",
						width, 10, width + 20, "Configure");
				}
			}
		}
	}

	@Test
	public void headerWithDifferentStyleFromHighlightedChildDoesNotLoseText()
	{
		Theme theme = theme(FontChoice.SANS, 100);
		String header = "Captured wind mote";
		int width = measuredHeaderWidth(theme, header) + 8;
		assertFullHeader(theme, header, width, 0, width + 1, "Attack");
	}

	/**
	 * The game draws its header at its rows' x. A header is therefore drawn exactly as a plain
	 * row with the same text is: at the same x, and fitted the same way when the text box is too
	 * small, instead of being inset by the corner radius or cut short with an ellipsis.
	 */
	@Test
	public void headerIsDrawnAndFittedLikeAPlainRowWithTheSameText()
	{
		String text = "Choose Option";
		for (FontChoice font : FontChoice.values())
		{
			for (int radius : new int[]{0, 6, 8})
			{
				for (int offset : new int[]{0, 3, 10, 20})
				{
					RightClickStylerConfig config = textOffset(offset);
					Theme theme = theme(font, 100).withRadius(radius).withTextShadow(false);
					int tight = measuredHeaderWidth(theme(FontChoice.RS_BOLD, 100), text) + 8;
					for (int width : new int[]{tight, tight + 80})
					{
						String where = font + " radius=" + radius + " offset=" + offset + " width=" + width;
						MenuRenderer.Row row = new MenuRenderer.Row(text, "", MenuRenderer.TargetKind.OTHER);
						Rectangle bounds = new Rectangle(10, 10, width, 37);
						BufferedImage image = new BufferedImage(width + 20, 60, BufferedImage.TYPE_INT_ARGB);
						Graphics2D graphics = image.createGraphics();
						try
						{
							new MenuRenderer().render(graphics, null, theme, config,
								Collections.singletonList(row), bounds, -1, 0, text);
						}
						finally
						{
							graphics.dispose();
						}
						Rectangle header = new Rectangle(bounds.x, bounds.y, width, MenuRenderer.HEADER_HEIGHT);
						Rectangle rowArea = new Rectangle(bounds.x, bounds.y + MenuRenderer.HEADER_HEIGHT,
							width, bounds.height - MenuRenderer.HEADER_HEIGHT);
						int rowColour = theme.text.getRGB();
						assertEquals("header starts at the row's x: " + where,
							leftmost(image, rowArea, rowColour), leftmost(image, header, HEADER_TEXT.getRGB()));
						assertArrayEquals("header fitted like the row: " + where,
							mask(image, rowArea, rowColour), mask(image, header, HEADER_TEXT.getRGB()));
						assertEquals("outside native menu right edge: " + where, 0, image.getRGB(bounds.x + width, 20));
					}
				}
			}
		}
	}

	private static RightClickStylerConfig textOffset(int offset)
	{
		return new RightClickStylerConfig()
		{
			@Override
			public int textOffsetX()
			{
				return offset;
			}
		};
	}

	private static int leftmost(BufferedImage image, Rectangle area, int colour)
	{
		for (int x = area.x; x < area.x + area.width; x++)
		{
			for (int y = area.y; y < area.y + area.height; y++)
			{
				if (image.getRGB(x, y) == colour)
				{
					return x;
				}
			}
		}
		return -1;
	}

	/** A position-independent bitmap of one colour's pixels within {@code area}. */
	private static boolean[] mask(BufferedImage image, Rectangle area, int colour)
	{
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = -1;
		int maxY = -1;
		for (int y = area.y; y < area.y + area.height; y++)
		{
			for (int x = area.x; x < area.x + area.width; x++)
			{
				if (image.getRGB(x, y) == colour)
				{
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		assertTrue("text is present", maxX >= 0);
		int width = maxX - minX + 1;
		boolean[] pixels = new boolean[width * (maxY - minY + 1)];
		for (int y = minY; y <= maxY; y++)
		{
			for (int x = minX; x <= maxX; x++)
			{
				pixels[(y - minY) * width + x - minX] = image.getRGB(x, y) == colour;
			}
		}
		return pixels;
	}

	private static Theme theme(FontChoice font, int opacity)
	{
		return BuiltInThemes.find("dark").withFont(font).withFontSize(13)
			.withOpacity(opacity).withHoverOpacity(opacity).withHeaderText(HEADER_TEXT)
			.withTextShadow(true).withShowBorder(false).withShowShadow(false);
	}

	private static int measuredHeaderWidth(Theme theme, String header)
	{
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			Font font = theme.font.isScalable()
				? new Font(theme.font.family, Font.PLAIN, theme.fontSize)
				: FontManager.getRunescapeBoldFont();
			return graphics.getFontMetrics(font).stringWidth(header);
		}
		finally
		{
			graphics.dispose();
		}
	}

	private static void assertFullHeader(Theme theme, String header, int width, int x, int canvasWidth,
		String child)
	{
		MenuRenderer.Row row = new MenuRenderer.Row(child, "", MenuRenderer.TargetKind.OTHER);
		Rectangle narrowBounds = new Rectangle(x, 10, width, 34);
		BufferedImage narrow = render(theme, header, row, narrowBounds, canvasWidth);
		BufferedImage wide = render(theme, header, row,
			new Rectangle(10, 10, Math.max(500, width + 100), 34), Math.max(520, width + 120));
		assertTrue("text is present: " + header, textPixels(narrow, narrowBounds).length > 0);
		assertArrayEquals("header text was ellipsized or clipped: " + header,
			textPixels(wide, new Rectangle(10, 10, wide.getWidth() - 20, 34)),
			textPixels(narrow, narrowBounds));
		if (x + width < canvasWidth)
		{
			assertEquals("outside native menu right edge", 0, narrow.getRGB(x + width, 20));
		}
		assertFalse("native bounds must stay inside canvas", x < 0 || x + width > canvasWidth);
	}

	private static BufferedImage render(Theme theme, String header, MenuRenderer.Row row,
		Rectangle bounds, int canvasWidth)
	{
		BufferedImage image = new BufferedImage(canvasWidth, 60, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			new MenuRenderer().render(graphics, null, theme, CONFIG,
				Collections.singletonList(row), bounds, -1, 0, header);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	/** A position-independent bitmap of header text, excluding the shadow and background. */
	private static int[] textPixels(BufferedImage image, Rectangle bounds)
	{
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = -1;
		int maxY = -1;
		for (int y = bounds.y; y < bounds.y + MenuRenderer.HEADER_HEIGHT; y++)
		{
			for (int x = bounds.x; x < bounds.x + bounds.width; x++)
			{
				if (isText(image.getRGB(x, y)))
				{
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}
		if (maxX < 0)
		{
			return new int[0];
		}
		int width = maxX - minX + 1;
		int[] pixels = new int[width * (maxY - minY + 1)];
		for (int y = minY; y <= maxY; y++)
		{
			for (int x = minX; x <= maxX; x++)
			{
				int argb = image.getRGB(x, y);
				pixels[(y - minY) * width + x - minX] = isText(argb) ? argb : 0;
			}
		}
		return pixels;
	}

	private static boolean isText(int argb)
	{
		return argb == HEADER_TEXT.getRGB() || argb == TAG_TEXT.getRGB();
	}
}
