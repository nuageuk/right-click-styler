package com.rightclickstyler;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class MutableMenuIconsTest
{
	private final RightClickStylerConfig config = new RightClickStylerConfig() {};
	private final Theme theme = BuiltInThemes.ALL.get(0).withOpacity(100).withFont(FontChoice.SANS);
	private final List<MenuRenderer.Row> rows = List.of(new MenuRenderer.Row("Take", "<img=0>Coins", MenuRenderer.TargetKind.ITEM));
	private final Rectangle bounds = new Rectangle(0, 0, 240, 50);

	@Test
	public void opaqueMenusDrawUpdatedIconsOnTopOfTheirBackground()
	{
		Client client = mock(Client.class);
		IndexedSprite[] icons = {sprite(Color.MAGENTA)};
		when(client.getModIcons()).thenReturn(icons);
		MenuRenderer renderer = new MenuRenderer();
		for (int opacity : new int[]{30, 100})
		{
			icons[0] = sprite(Color.MAGENTA);
			assertTrue(contains(render(renderer, client, opacity), Color.MAGENTA));
			icons[0] = sprite(Color.CYAN);
			BufferedImage changed = render(renderer, client, opacity);
			assertTrue(contains(changed, Color.CYAN));
			assertFalse(contains(changed, Color.MAGENTA));
		}
	}

	@Test
	public void submenuCacheDetectsSpriteReplacementInTheSameArray()
	{
		IndexedSprite[] icons = {sprite(Color.MAGENTA)};
		SubmenuImageCache.Menu[] menus = {SubmenuImageCache.menu(bounds, "Coins", 0, -1, rows)};
		SubmenuImageCache cache = new SubmenuImageCache();
		cache.rendered(SubmenuImageCache.capture(theme, config, icons, 3, bounds, menus));
		assertEquals(SubmenuImageCache.Miss.HIT, cache.lookup(SubmenuImageCache.capture(theme, config, icons, 3, bounds, menus)));
		icons[0] = sprite(Color.CYAN);
		assertEquals(SubmenuImageCache.Miss.ICONS, cache.lookup(SubmenuImageCache.capture(theme, config, icons, 3, bounds, menus)));
	}

	private BufferedImage render(MenuRenderer renderer, Client client, int opacity)
	{
		BufferedImage image = new BufferedImage(240, 50, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			renderer.render(graphics, client, theme.withOpacity(opacity), config, rows, bounds, -1, 3);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	private static IndexedSprite sprite(Color color)
	{
		IndexedSprite sprite = mock(IndexedSprite.class);
		when(sprite.getWidth()).thenReturn(2);
		when(sprite.getHeight()).thenReturn(2);
		when(sprite.getPixels()).thenReturn(new byte[]{1, 1, 1, 1});
		when(sprite.getPalette()).thenReturn(new int[]{0, color.getRGB() & 0xffffff});
		return sprite;
	}

	private static boolean contains(BufferedImage image, Color color)
	{
		for (int y = 0; y < image.getHeight(); y++)
			for (int x = 0; x < image.getWidth(); x++)
				if (image.getRGB(x, y) == color.getRGB())
					return true;
		return false;
	}
}
