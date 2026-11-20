package com.rightclickstyler;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MenuRendererOpacityTest
{
	private static final Rectangle BOUNDS = new Rectangle(10, 10, 200, 70);
	private static final RightClickStylerConfig CONFIG = new RightClickStylerConfig() {};

	@Test
	public void backgroundHeaderAndHoverUseFullOpacityRange()
	{
		for (int opacity : new int[]{0, 1, 10, 40, 100})
		{
			Theme theme = theme(opacity).withShowBorder(false).withShowShadow(false);
			BufferedImage image = render(theme,
				Collections.singletonList(new MenuRenderer.Row("", "", MenuRenderer.TargetKind.OTHER)), 0);
			int expected = (int) Math.round(opacity * 255.0 / 100.0);
			int background = alpha(image, 190, 60);
			int header = alpha(image, 190, 15);
			int hover = alpha(image, 190, 35);
			int expectedLayered = (int) Math.round(expected + expected * (255 - expected) / 255.0);
			assertEquals("background " + opacity, expected, background);
			assertEquals("header " + opacity, expectedLayered, header, 1);
			assertEquals("hover " + opacity, expectedLayered, hover, 1);
		}
	}

	@Test
	public void zeroOpacityKeepsTextBorderAndShadowVisible()
	{
		Theme theme = theme(0);
		BufferedImage image = render(theme,
			Collections.singletonList(new MenuRenderer.Row("Take", "<col=ff9040>Item", MenuRenderer.TargetKind.ITEM)), 0);
		assertTrue("header text", anyVisible(image, 15, 12, 120, 29));
		assertTrue("row text", anyVisible(image, 15, 30, 120, 44));
		assertTrue("border", alpha(image, 10, 50) > 0);
		assertTrue("shadow", anyVisible(image, 210, 40, 214, 78));
	}

	@Test
	public void backgroundAndHoverFillHaveIndependentAlpha()
	{
		for (int[] pair : new int[][]{{0, 100}, {100, 0}, {30, 70}, {0, 0}})
		{
			Theme theme = theme(pair[0]).withHoverOpacity(pair[1])
				.withShowBorder(false).withShowShadow(false);
			BufferedImage image = render(theme,
				Collections.singletonList(new MenuRenderer.Row("", "", MenuRenderer.TargetKind.OTHER)), 0);
			int bg = (int) Math.round(pair[0] * 255.0 / 100.0);
			int hover = (int) Math.round(pair[1] * 255.0 / 100.0);
			int layered = (int) Math.round(bg + hover * (255 - bg) / 255.0);
			assertEquals("background " + pair[0] + "/" + pair[1], bg, alpha(image, 190, 60));
			assertEquals("header " + pair[0] + "/" + pair[1],
				(int) Math.round(bg + bg * (255 - bg) / 255.0), alpha(image, 190, 15));
			assertEquals("hover " + pair[0] + "/" + pair[1], layered, alpha(image, 190, 35));
		}
	}

	@Test
	public void zeroHoverFillLeavesOpaqueBackgroundUnchanged()
	{
		Theme theme = theme(100).withHoverOpacity(0).withShowBorder(false).withShowShadow(false);
		BufferedImage image = render(theme,
			Collections.singletonList(new MenuRenderer.Row("", "", MenuRenderer.TargetKind.OTHER)), 0);
		assertEquals(image.getRGB(190, 60), image.getRGB(190, 35));
	}

	@Test
	public void outlineBarAndTextAccentsStayVisibleAtZeroFillOpacity()
	{
		Theme base = theme(0).withHoverOpacity(0).withShowBorder(false).withShowShadow(false);
		java.util.List<MenuRenderer.Row> rows = Collections.singletonList(
			new MenuRenderer.Row("Take", "", MenuRenderer.TargetKind.OTHER));
		BufferedImage outline = render(base.withHoverStyle(HoverStyle.OUTLINE), rows, 0);
		assertTrue("outline", anyVisible(outline, 10, 30, 210, 45));
		assertEquals("outline does not fill", 0, alpha(outline, 190, 35));

		BufferedImage bar = render(base.withHoverStyle(HoverStyle.BAR), rows, 0);
		assertTrue("accent bar", anyVisible(bar, 10, 30, 15, 45));
		assertEquals("bar fill is transparent", 0, alpha(bar, 190, 35));
		BufferedImage filledBar = render(base.withHoverOpacity(70).withHoverStyle(HoverStyle.BAR), rows, 0);
		assertEquals("bar uses hover opacity", (int) Math.round(70 * 255.0 / 100.0),
			alpha(filledBar, 190, 35));
		assertEquals("bar accent stays opaque", 255, alpha(filledBar, 11, 35));

		BufferedImage text = render(base.withHoverStyle(HoverStyle.TEXT)
			.withAccent(Color.GREEN), rows, 0);
		assertTrue("accent text", hasColour(text, Color.GREEN, 15, 30, 90, 45));
		assertEquals("text style does not fill", 0, alpha(text, 190, 35));
		BufferedImage none = render(base.withHoverOpacity(100).withHoverStyle(HoverStyle.NONE), rows, 0);
		assertEquals("none style ignores hover opacity", 0, alpha(none, 190, 35));
	}

	@Test
	public void transparentBackgroundWithVisibleHoverKeepsTextAndDecorations()
	{
		Theme theme = theme(0).withHoverOpacity(80).withAccent(Color.GREEN);
		BufferedImage image = render(theme,
			Collections.singletonList(new MenuRenderer.Row("Take", "<col=ff9040>Item", MenuRenderer.TargetKind.ITEM)), 0);
		assertTrue("hover fill", alpha(image, 190, 35) > 0);
		assertTrue("row text", anyVisible(image, 15, 30, 120, 44));
		assertTrue("border", alpha(image, 10, 50) > 0);
		assertTrue("shadow", anyVisible(image, 210, 40, 214, 78));
	}

	@Test
	public void actionHighlightColourDoesNotFollowEitherOpacity()
	{
		java.util.List<MenuRenderer.Row> rows = Collections.singletonList(
			new MenuRenderer.Row("Attack", "", MenuRenderer.TargetKind.OTHER));
		for (int[] pair : new int[][]{{0, 0}, {0, 100}, {100, 20}})
		{
			BufferedImage image = render(theme(pair[0]).withHoverOpacity(pair[1])
				.withShowBorder(false).withShowShadow(false), rows, 0);
			assertTrue("highlight colour " + pair[0] + "/" + pair[1],
				hasColour(image, new Color(0xFF5C5C), 15, 30, 100, 45));
		}
	}

	private static Theme theme(int opacity)
	{
		return BuiltInThemes.find(BuiltInThemes.DEFAULT_ID).withOpacity(opacity).withHoverOpacity(opacity)
			.withFont(FontChoice.SANS).withHoverStyle(HoverStyle.FILL)
			.withRadius(0).withTextShadow(false).withShowBorder(true).withShowShadow(true);
	}

	private static BufferedImage render(Theme theme, java.util.List<MenuRenderer.Row> rows, int hovered)
	{
		BufferedImage image = new BufferedImage(240, 90, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			new MenuRenderer().render(graphics, null, theme, CONFIG, rows, BOUNDS, hovered, 0);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	private static int alpha(BufferedImage image, int x, int y)
	{
		return image.getRGB(x, y) >>> 24;
	}

	private static boolean anyVisible(BufferedImage image, int minX, int minY, int maxX, int maxY)
	{
		for (int y = minY; y < maxY; y++)
		{
			for (int x = minX; x < maxX; x++)
			{
				if (alpha(image, x, y) > 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean hasColour(BufferedImage image, Color colour,
		int minX, int minY, int maxX, int maxY)
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
