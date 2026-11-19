package com.rightclickstyler;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Reuse of the submenu's themed image. The probe, the exact-coverage
 * decision and every structural and lifecycle check still run on every frame; only the theme
 * render is skipped, and only when every input that render reads is equal to the inputs of the
 * image already in the buffer.
 *
 * <p>The key is the complete input of {@link MenuRenderer} as the overlay calls it: the theme
 * (immutable, compared by identity), every plugin config item, the mod-icon source,
 * the local combat level, the image region, and for each drawn menu its
 * bounds, header, root scroll offset, hovered row and every row's option, target, kind and
 * submenu arrow. Contents are compared by value, never by menu or entry identity, and never by
 * hash, so no collision can reuse a wrong image.
 */
final class SubmenuImageCache
{
	/** Why an image could not be reused; {@link #HIT} when it was. */
	enum Miss
	{
		HIT, EMPTY, UNCACHEABLE, THEME, CONFIG, ICONS, LEVEL, REGION, CHAIN, BOUNDS, HEADER, SCROLL,
		HOVER, ROWS
	}

	// Explicit config snapshot. Keep this list in sync with RightClickStylerConfig when adding
	// settings that affect rendering. Theme values are captured by the immutable theme reference.
	private static final int CONFIG_ITEM_COUNT = 18;

	private Key cached;

	/** The rendered image no longer matches {@link #cached}: reallocated, or a render began. */
	void invalidate()
	{
		cached = null;
	}

	Miss lookup(Key key)
	{
		if (key == null)
		{
			return Miss.UNCACHEABLE;
		}
		return cached == null ? Miss.EMPTY : cached.difference(key);
	}

	/** Call only after the image has been completely rendered from {@code key}'s inputs. */
	void rendered(Key key)
	{
		cached = key;
	}

	/**
	 * @return the key, or null when an input cannot be read reliably (the frame then renders
	 * uncached and nothing is stored)
	 */
	static Key capture(Theme theme, RightClickStylerConfig config, Object modIcons, int localLevel,
		Rectangle region, Menu[] menus)
	{
		Object[] values;
		try
		{
			values = new Object[]{
				config.showSidebarButton(), config.activeTheme(),
				config.highlightsEnabled(), config.highlightsBold(), config.highlightRules(),
				config.targetMode(), config.npcColour(), config.playerColour(),
				config.itemColour(), config.objectColour(), config.otherColour(),
				config.combatLevelMode(), config.lowerLevelColour(), config.sameLevelColour(),
				config.higherLevelColour(), config.textOffsetX(), config.baselineNudge(),
				config.showHitRows()
			};
		}
		catch (RuntimeException e)
		{
			return null;
		}
		return new Key(theme, values, modIcons, localLevel, new Rectangle(region), menus);
	}

	/** One drawn menu, captured from the rows the renderer is about to draw. */
	static Menu menu(Rectangle bounds, String header, int scroll, int hovered, List<MenuRenderer.Row> rows)
	{
		return menu(bounds, header, null, scroll, hovered, rows);
	}

	/** @param headerKind how the header's target colours resolve; see {@link MenuRenderer} */
	static Menu menu(Rectangle bounds, String header, MenuRenderer.TargetKind headerKind, int scroll,
		int hovered, List<MenuRenderer.Row> rows)
	{
		int count = rows.size();
		String[] options = new String[count];
		String[] targets = new String[count];
		int[] flags = new int[count];
		for (int i = 0; i < count; i++)
		{
			MenuRenderer.Row row = rows.get(i);
			options[i] = row.option();
			targets[i] = row.target();
			flags[i] = row.flags();
		}
		return new Menu(new Rectangle(bounds), header, headerKind, scroll, hovered, options, targets, flags);
	}

	static final class Menu
	{
		private final Rectangle bounds;
		private final String header;
		private final MenuRenderer.TargetKind headerKind;
		private final int scroll;
		private final int hovered;
		private final String[] options;
		private final String[] targets;
		private final int[] flags;

		private Menu(Rectangle bounds, String header, MenuRenderer.TargetKind headerKind, int scroll,
			int hovered, String[] options, String[] targets, int[] flags)
		{
			this.bounds = bounds;
			this.header = header;
			this.headerKind = headerKind;
			this.scroll = scroll;
			this.hovered = hovered;
			this.options = options;
			this.targets = targets;
			this.flags = flags;
		}
	}

	static final class Key
	{
		private final Theme theme;
		private final Object[] config;
		private final Object modIcons;
		private final int localLevel;
		private final Rectangle region;
		private final Menu[] menus;

		private Key(Theme theme, Object[] config, Object modIcons, int localLevel, Rectangle region, Menu[] menus)
		{
			this.theme = theme;
			this.config = config;
			this.modIcons = modIcons;
			this.localLevel = localLevel;
			this.region = region;
			this.menus = menus;
		}

		/** The first input class that differs, cheapest checks first; HIT when all are equal. */
		Miss difference(Key other)
		{
			if (theme != other.theme)
			{
				return Miss.THEME;
			}
			if (!Arrays.equals(config, other.config))
			{
				return Miss.CONFIG;
			}
			if (modIcons != other.modIcons)
			{
				return Miss.ICONS;
			}
			if (localLevel != other.localLevel)
			{
				return Miss.LEVEL;
			}
			if (!region.equals(other.region))
			{
				return Miss.REGION;
			}
			if (menus.length != other.menus.length)
			{
				return Miss.CHAIN;
			}
			Miss found = Miss.HIT;
			for (int depth = 0; depth < menus.length && found == Miss.HIT; depth++)
			{
				Menu a = menus[depth];
				Menu b = other.menus[depth];
				if (!a.bounds.equals(b.bounds))
				{
					found = Miss.BOUNDS;
				}
				else if (!Objects.equals(a.header, b.header) || a.headerKind != b.headerKind)
				{
					found = Miss.HEADER;
				}
				else if (a.scroll != b.scroll)
				{
					found = Miss.SCROLL;
				}
				else if (!Arrays.equals(a.options, b.options) || !Arrays.equals(a.targets, b.targets)
					|| !Arrays.equals(a.flags, b.flags))
				{
					found = Miss.ROWS;
				}
			}
			if (found != Miss.HIT)
			{
				return found;
			}
			// Hover last: when everything else matches, it alone decides.
			for (int depth = 0; depth < menus.length; depth++)
			{
				if (menus[depth].hovered != other.menus[depth].hovered)
				{
					return Miss.HOVER;
				}
			}
			return Miss.HIT;
		}
	}

	static int configItemCount()
	{
		return CONFIG_ITEM_COUNT;
	}
}
