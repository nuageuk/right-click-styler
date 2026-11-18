package com.rightclickstyler;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PrototypeImageCacheTest
{
	private static final Theme THEME = BuiltInThemes.ALL.get(0);
	private static final RightClickStylerConfig CONFIG = new RightClickStylerConfig() { };

	private static List<MenuRenderer.Row> rows(String... options)
	{
		List<MenuRenderer.Row> rows = new ArrayList<>();
		for (String option : options)
		{
			rows.add(new MenuRenderer.Row(option, "<col=ffff00>Target", MenuRenderer.TargetKind.NPC));
		}
		return rows;
	}

	private static PrototypeImageCache.Key key(Theme theme, RightClickStylerConfig config, Object icons, int level,
		Rectangle region, PrototypeImageCache.Menu... menus)
	{
		return PrototypeImageCache.capture(theme, config, icons, level, region, menus);
	}

	private static PrototypeImageCache.Menu root(int hovered, int scroll, String... options)
	{
		return PrototypeImageCache.menu(new Rectangle(100, 0, 200, 400), "Choose Option", scroll, hovered, rows(options));
	}

	@Test
	public void everyConfigItemIsPartOfTheKey()
	{
		assertEquals("all @ConfigItem methods of RightClickStylerConfig", 18, PrototypeImageCache.configItemCount());
	}

	@Test
	public void eachInputClassIsDetected()
	{
		Rectangle region = new Rectangle(97, 0, 206, 403);
		PrototypeImageCache.Key base = key(THEME, CONFIG, null, 90, region, root(2, 0, "Walk", "Talk-to"));
		assertEquals(PrototypeImageCache.Miss.HIT,
			key(THEME, CONFIG, null, 90, new Rectangle(region), root(2, 0, new String("Walk"), "Talk-to")).difference(base));
		assertEquals(PrototypeImageCache.Miss.THEME,
			key(BuiltInThemes.ALL.get(1), CONFIG, null, 90, region, root(2, 0, "Walk", "Talk-to")).difference(base));
		RightClickStylerConfig hitRows = new RightClickStylerConfig()
		{
			@Override
			public boolean showHitRows()
			{
				return true;
			}
		};
		assertEquals(PrototypeImageCache.Miss.CONFIG,
			key(THEME, hitRows, null, 90, region, root(2, 0, "Walk", "Talk-to")).difference(base));
		assertEquals(PrototypeImageCache.Miss.ICONS,
			key(THEME, CONFIG, new Object(), 90, region, root(2, 0, "Walk", "Talk-to")).difference(base));
		assertEquals(PrototypeImageCache.Miss.LEVEL,
			key(THEME, CONFIG, null, 91, region, root(2, 0, "Walk", "Talk-to")).difference(base));
		assertEquals(PrototypeImageCache.Miss.REGION,
			key(THEME, CONFIG, null, 90, new Rectangle(98, 0, 206, 403), root(2, 0, "Walk", "Talk-to")).difference(base));
		assertEquals(PrototypeImageCache.Miss.CHAIN, key(THEME, CONFIG, null, 90, region, root(2, 0, "Walk", "Talk-to"),
			root(0, 0, "Child")).difference(base));
		assertEquals(PrototypeImageCache.Miss.SCROLL,
			key(THEME, CONFIG, null, 90, region, root(2, 1, "Walk", "Talk-to")).difference(base));
		assertEquals(PrototypeImageCache.Miss.ROWS,
			key(THEME, CONFIG, null, 90, region, root(2, 0, "Walk", "Talk-to <col=ff0000>")).difference(base));
		assertEquals(PrototypeImageCache.Miss.HOVER,
			key(THEME, CONFIG, null, 90, region, root(1, 0, "Walk", "Talk-to")).difference(base));
	}

	@Test
	public void independentOpacityFontAndColourChangesInvalidateTheImage()
	{
		Rectangle region = new Rectangle(0, 0, 200, 200);
		PrototypeImageCache.Menu menu = root(0, 0, "Open");
		PrototypeImageCache.Key base = key(THEME, CONFIG, null, 0, region, menu);
		assertEquals(PrototypeImageCache.Miss.THEME,
			key(THEME.withOpacity(0), CONFIG, null, 0, region, menu).difference(base));
		assertEquals(PrototypeImageCache.Miss.THEME,
			key(THEME.withHoverOpacity(0), CONFIG, null, 0, region, menu).difference(base));
		assertEquals(PrototypeImageCache.Miss.THEME,
			key(THEME.withFontSize(THEME.fontSize + 1), CONFIG, null, 0, region, menu).difference(base));
		assertEquals(PrototypeImageCache.Miss.THEME,
			key(THEME.withText(Color.RED), CONFIG, null, 0, region, menu).difference(base));
		RightClickStylerConfig changedColour = new RightClickStylerConfig()
		{
			@Override
			public Color npcColour()
			{
				return Color.BLUE;
			}
		};
		assertEquals(PrototypeImageCache.Miss.CONFIG,
			key(THEME, changedColour, null, 0, region, menu).difference(base));
	}

	@Test
	public void boundsHeaderAndArrowsAreRenderingInputs()
	{
		Rectangle region = new Rectangle(0, 0, 400, 400);
		List<MenuRenderer.Row> plain = rows("Open");
		List<MenuRenderer.Row> arrow = rows("Open");
		arrow.get(0).set("Open", "<col=ffff00>Target", MenuRenderer.TargetKind.NPC, true, false);
		PrototypeImageCache.Key base = key(THEME, CONFIG, null, 0, region,
			PrototypeImageCache.menu(new Rectangle(10, 10, 100, 40), "Bank", 0, -1, plain));
		assertEquals(PrototypeImageCache.Miss.BOUNDS, key(THEME, CONFIG, null, 0, region,
			PrototypeImageCache.menu(new Rectangle(10, 11, 100, 40), "Bank", 0, -1, plain)).difference(base));
		assertEquals(PrototypeImageCache.Miss.HEADER, key(THEME, CONFIG, null, 0, region,
			PrototypeImageCache.menu(new Rectangle(10, 10, 100, 40), "Banker", 0, -1, plain)).difference(base));
		assertEquals(PrototypeImageCache.Miss.ROWS, key(THEME, CONFIG, null, 0, region,
			PrototypeImageCache.menu(new Rectangle(10, 10, 100, 40), "Bank", 0, -1, arrow)).difference(base));
	}

	@Test
	public void headerTargetKindAndItemColourInvalidateTheImage()
	{
		Rectangle region = new Rectangle(0, 0, 400, 400);
		Rectangle bounds = new Rectangle(10, 10, 100, 40);
		String header = "<col=ff9040>Drakan's medallion";
		PrototypeImageCache.Key base = key(THEME, CONFIG, null, 0, region,
			PrototypeImageCache.menu(bounds, header, MenuRenderer.TargetKind.ITEM, 0, -1, rows("Ver Sinhaza")));
		assertEquals(PrototypeImageCache.Miss.HIT, key(THEME, CONFIG, null, 0, region,
			PrototypeImageCache.menu(bounds, header, MenuRenderer.TargetKind.ITEM, 0, -1, rows("Ver Sinhaza")))
			.difference(base));
		assertEquals(PrototypeImageCache.Miss.HEADER, key(THEME, CONFIG, null, 0, region,
			PrototypeImageCache.menu(bounds, header, null, 0, -1, rows("Ver Sinhaza"))).difference(base));
		RightClickStylerConfig itemColour = new RightClickStylerConfig()
		{
			@Override
			public Color itemColour()
			{
				return Color.MAGENTA;
			}
		};
		assertEquals(PrototypeImageCache.Miss.CONFIG, key(THEME, itemColour, null, 0, region,
			PrototypeImageCache.menu(bounds, header, MenuRenderer.TargetKind.ITEM, 0, -1, rows("Ver Sinhaza")))
			.difference(base));
		RightClickStylerConfig customMode = new RightClickStylerConfig()
		{
			@Override
			public TargetMode targetMode()
			{
				return TargetMode.CUSTOM;
			}
		};
		assertEquals(PrototypeImageCache.Miss.CONFIG, key(THEME, customMode, null, 0, region,
			PrototypeImageCache.menu(bounds, header, MenuRenderer.TargetKind.ITEM, 0, -1, rows("Ver Sinhaza")))
			.difference(base));
	}

	@Test
	public void rowsAreCopiedNotReferenced()
	{
		// The overlay reuses its Row objects every frame; a key must not change with them.
		List<MenuRenderer.Row> pooled = rows("Before");
		PrototypeImageCache.Key before = key(THEME, CONFIG, null, 0, new Rectangle(0, 0, 10, 10),
			PrototypeImageCache.menu(new Rectangle(0, 0, 10, 10), null, 0, -1, pooled));
		pooled.get(0).set("After", "<col=ffff00>Target", MenuRenderer.TargetKind.NPC);
		PrototypeImageCache.Key after = key(THEME, CONFIG, null, 0, new Rectangle(0, 0, 10, 10),
			PrototypeImageCache.menu(new Rectangle(0, 0, 10, 10), null, 0, -1, pooled));
		assertEquals(PrototypeImageCache.Miss.ROWS, after.difference(before));
	}

	@Test
	public void anUnreadableConfigIsNeverCached()
	{
		RightClickStylerConfig broken = new RightClickStylerConfig()
		{
			@Override
			public int baselineNudge()
			{
				throw new IllegalStateException("config read failed");
			}
		};
		assertNull(key(THEME, broken, null, 0, new Rectangle(0, 0, 1, 1), root(-1, 0, "Walk")));
		PrototypeImageCache cache = new PrototypeImageCache();
		assertEquals(PrototypeImageCache.Miss.UNCACHEABLE, cache.lookup(null));
	}

	@Test
	public void lookupNeedsACompletedRender()
	{
		PrototypeImageCache cache = new PrototypeImageCache();
		PrototypeImageCache.Key key = key(THEME, CONFIG, null, 0, new Rectangle(0, 0, 1, 1), root(-1, 0, "Walk"));
		assertEquals(PrototypeImageCache.Miss.EMPTY, cache.lookup(key));
		cache.rendered(key);
		assertEquals(PrototypeImageCache.Miss.HIT, cache.lookup(key));
		cache.invalidate();
		assertEquals(PrototypeImageCache.Miss.EMPTY, cache.lookup(key));
	}

	@Test
	public void perfSamplesReportPercentilesAndCountOverflow()
	{
		PerfTrace.Samples samples = new PerfTrace.Samples();
		for (int i = 1; i <= 100; i++)
		{
			samples.add(i);
		}
		assertArrayEquals(new long[]{51, 91, 100, 100}, samples.percentiles());
		assertEquals(5050, samples.sum());
		samples.clear();
		assertEquals(0, samples.percentiles().length);
	}
}
