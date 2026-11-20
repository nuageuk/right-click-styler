package com.rightclickstyler;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Arrays;
import org.junit.Test;
import net.runelite.client.ui.FontManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuRendererGeometryTest
{
	private static final RightClickStylerConfig CONFIG = new RightClickStylerConfig() {};

	@Test
	public void completeFormattedRowsFitNativeBoundsAcrossFontsAndOpacity()
	{
		for (FontChoice font : new FontChoice[]{FontChoice.RS_BOLD, FontChoice.SANS, FontChoice.SERIF})
		{
			for (int opacity : new int[]{0, 100})
			{
				Theme theme = theme(font, opacity);
				MenuRenderer.Row row = new MenuRenderer.Row(
					"<col=ff0000>Inspect</col> the enchanted artifact",
					"<col=00ff00>END</col>", MenuRenderer.TargetKind.OTHER);
				Rectangle bounds = new Rectangle(20, 10, 112, 49);
				BufferedImage image = render(theme, row, bounds, 180);
				assertTrue(font + " opacity=" + opacity + " first coloured run",
					hasColour(image, Color.RED, bounds.x, bounds.x + bounds.width, 29, 44));
				assertTrue(font + " opacity=" + opacity + " final coloured run",
					hasColour(image, Color.GREEN, bounds.x, bounds.x + bounds.width, 29, 44));
				assertEquals("left canvas outside menu", 0, image.getRGB(bounds.x - 1, 35));
				assertEquals("right canvas outside menu", 0,
					image.getRGB(bounds.x + bounds.width, 35));
			}
		}
	}

	@Test
	public void nestedRowsKeepTheirEndTextAndArrowSpaceAtCanvasEdges()
	{
		Theme theme = theme(FontChoice.SANS, 100);
		for (boolean opensLeft : new boolean[]{false, true})
		{
			Rectangle bounds = new Rectangle(opensLeft ? 0 : 108, 10, 112, 49);
			MenuRenderer.Row row = new MenuRenderer.Row("Inspect the enchanted artifact",
				"<col=00ff00>END</col>", MenuRenderer.TargetKind.OTHER);
			row.set(row.option(), row.target(), row.kind(), true, opensLeft);
			BufferedImage image = render(theme, row, bounds, 221);
			assertTrue("nested final text opensLeft=" + opensLeft,
				hasColour(image, Color.GREEN, bounds.x, bounds.x + bounds.width, 29, 44));
			assertEquals("outside right edge", 0, image.getRGB(bounds.x + bounds.width, 35));
			if (!opensLeft)
			{
				assertFalse("text leaves right arrow space",
					hasColour(image, Color.GREEN, bounds.x + bounds.width - 16,
						bounds.x + bounds.width, 29, 44));
			}
		}
	}

	@Test
	public void swapShiftClickRowKeepsNaturalPixelsBesideRightArrow()
	{
		for (int opacity : new int[]{0, 100})
		{
			Theme theme = theme(FontChoice.RS_BOLD, opacity);
			MenuRenderer.Row row = new MenuRenderer.Row("Swap shift-click",
				"<col=ff9040>Saw</col>", MenuRenderer.TargetKind.ITEM);
			row.set(row.option(), row.target(), row.kind(), true, false);
			int content = measuredWidth(theme, row);
			int width = content + 3 + 16 + 1;
			Rectangle narrowBounds = new Rectangle(10, 10, width, 49);
			BufferedImage narrow = render(theme, row, narrowBounds, width + 11);
			BufferedImage wide = render(theme, row, new Rectangle(10, 10, width + 100, 49), width + 111);
			assertTrue("full target remains visible", hasColour(narrow, new Color(0xFF9040),
				10, 10 + width - 16, 29, 44));
			assertTrue("row is rendered at natural proportions",
				Arrays.equals(rowTextPixels(narrow, 10, width - 16, theme.text.getRGB()),
					rowTextPixels(wide, 10, width - 16, theme.text.getRGB())));
		}
	}

	/**
	 * Without the optional drop shadow, nothing the renderer draws (header, rows, hover, arrows,
	 * text shadow, border) reaches outside the game's own menu rectangle, whatever the font,
	 * text offset, baseline nudge or hover style.
	 */
	@Test
	public void nothingIsPaintedOutsideTheNativeRectangleAcrossSettings()
	{
		MenuRenderer.Row plain = new MenuRenderer.Row("<col=ff0000>Inspect</col> the enchanted artifact",
			"<col=00ff00>END</col> (level-126)", MenuRenderer.TargetKind.NPC);
		MenuRenderer.Row right = new MenuRenderer.Row("Swap shift-click", "<col=ff9040>Saw", MenuRenderer.TargetKind.ITEM);
		right.set(right.option(), right.target(), right.kind(), true, false);
		MenuRenderer.Row left = new MenuRenderer.Row("Swap left-click", "<col=ff9040>Saw", MenuRenderer.TargetKind.ITEM);
		left.set(left.option(), left.target(), left.kind(), true, true);
		java.util.List<MenuRenderer.Row> rows = Arrays.asList(plain, right, left);
		Rectangle bounds = new Rectangle(12, 12, 96, 22 + 15 * rows.size());
		for (FontChoice font : FontChoice.values())
		{
			for (int size : new int[]{Limits.MIN_FONT_SIZE, Limits.MAX_FONT_SIZE})
			{
				for (int offset : new int[]{Limits.MIN_TEXT_OFFSET_X, 3, Limits.MAX_TEXT_OFFSET_X})
				{
					for (int nudge : new int[]{Limits.MIN_BASELINE_NUDGE, 0, Limits.MAX_BASELINE_NUDGE})
					{
						for (HoverStyle hover : HoverStyle.values())
						{
							Theme theme = BuiltInThemes.find("dark").withFont(font).withFontSize(size)
								.withRadius(Limits.MAX_RADIUS).withHoverStyle(hover).withOpacity(100)
								.withShowBorder(true).withShowShadow(false).withTextShadow(true);
							RightClickStylerConfig config = new RightClickStylerConfig()
							{
								@Override
								public int textOffsetX()
								{
									return offset;
								}

								@Override
								public int baselineNudge()
								{
									return nudge;
								}
							};
							BufferedImage image = new BufferedImage(bounds.x * 2 + bounds.width,
								bounds.y * 2 + bounds.height, BufferedImage.TYPE_INT_ARGB);
							Graphics2D graphics = image.createGraphics();
							try
							{
								new MenuRenderer().render(graphics, null, theme, config, rows, bounds, 1, 0,
									"<col=ff9040>Captured wind mote of the enchanted artifact</col>");
							}
							finally
							{
								graphics.dispose();
							}
							for (int y = 0; y < image.getHeight(); y++)
							{
								for (int x = 0; x < image.getWidth(); x++)
								{
									if (!bounds.contains(x, y))
									{
										assertEquals(font + " size=" + size + " offset=" + offset + " nudge=" + nudge
											+ " hover=" + hover + " at " + x + "," + y, 0, image.getRGB(x, y));
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private static int measuredWidth(Theme theme, MenuRenderer.Row row)
	{
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			FontMetrics metrics = graphics.getFontMetrics(FontManager.getRunescapeBoldFont());
			return metrics.stringWidth(row.option()) + metrics.charWidth(' ')
				+ metrics.stringWidth("Saw");
		}
		finally
		{
			graphics.dispose();
		}
	}

	private static int[] rowTextPixels(BufferedImage image, int x, int width, int optionColour)
	{
		int targetColour = new Color(0xFF9040).getRGB();
		int[] pixels = new int[width * 15];
		for (int y = 29; y < 44; y++)
		{
			for (int i = 0; i < width; i++)
			{
				int colour = image.getRGB(x + i, y);
				pixels[(y - 29) * width + i] = colour == targetColour
					|| colour == optionColour ? colour : 0;
			}
		}
		return pixels;
	}

	private static Theme theme(FontChoice font, int opacity)
	{
		return BuiltInThemes.find(BuiltInThemes.DEFAULT_ID).withFont(font).withFontSize(13)
			.withOpacity(opacity).withHoverOpacity(opacity)
			.withShowBorder(false).withShowShadow(false).withTextShadow(false);
	}

	private static BufferedImage render(Theme theme, MenuRenderer.Row row, Rectangle bounds, int canvasWidth)
	{
		BufferedImage image = new BufferedImage(canvasWidth, 65, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			new MenuRenderer().render(graphics, null, theme, CONFIG,
				Collections.singletonList(row), bounds, -1, 0);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	private static boolean hasColour(BufferedImage image, Color colour,
		int minX, int maxX, int minY, int maxY)
	{
		for (int y = minY; y < maxY; y++)
		{
			for (int x = minX; x < maxX; x++)
			{
				if (image.getRGB(x, y) == colour.getRGB())
				{
					return true;
				}
			}
		}
		return false;
	}
}
