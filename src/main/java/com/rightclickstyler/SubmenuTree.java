package com.rightclickstyler;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;

/** A bounded, single-frame snapshot of the public menu hierarchy. */
final class SubmenuTree
{
	static final int MAX_DEPTH = 8;
	static final int MAX_ENTRIES = 512;
	// One root with the maximum permitted number of direct child menus. Nested
	// trees share this budget, and the total entry budget limits row storage.
	static final int MAX_MENUS = MAX_ENTRIES + 1;
	static final int MAX_TOTAL_ENTRIES = MAX_ENTRIES * (MAX_DEPTH + 1);

	final Menu root;
	final List<Node> nodes = new ArrayList<>();
	final List<Rectangle> candidateBounds = new ArrayList<>();
	final List<Integer> candidateParents = new ArrayList<>();
	private int totalEntries;
	private final int skippedRootRows;

	private SubmenuTree(Menu root, MenuEntry[] rootEntries, int skippedRootRows)
	{
		this.root = root;
		this.skippedRootRows = skippedRootRows;
		collect(root, rootEntries, -1, -1, null, false, 0, new IdentityHashMap<>());
	}

	static SubmenuTree capture(Menu root)
	{
		return capture(root, root == null ? null : root.getMenuEntries());
	}

	static SubmenuTree capture(Menu root, MenuEntry[] rootEntries)
	{
		return capture(root, rootEntries, 0);
	}

	static SubmenuTree capture(Menu root, MenuEntry[] rootEntries, int skippedRootRows)
	{
		if (root == null)
		{
			throw new IllegalArgumentException("missing root menu");
		}
		MenuEntry[] entries = rootEntries == null ? root.getMenuEntries() : rootEntries;
		if (entries != null && (skippedRootRows < 0 || skippedRootRows > entries.length))
		{
			throw new IllegalArgumentException("invalid root scroll skip=" + skippedRootRows
				+ " entries=" + entries.length);
		}
		return new SubmenuTree(root, entries, skippedRootRows);
	}

	private void collect(Menu menu, MenuEntry[] suppliedEntries, int parentNode,
		int parentEntryIndex, String header, boolean headerFromTarget, int depth,
		IdentityHashMap<Menu, Boolean> seen)
	{
		if (depth > MAX_DEPTH)
		{
			throw new IllegalArgumentException("menu depth limit exceeded depth=" + depth
				+ " limit=" + MAX_DEPTH);
		}
		if (nodes.size() >= MAX_MENUS)
		{
			throw new IllegalArgumentException("menu count limit exceeded nodes=" + nodes.size()
				+ " limit=" + MAX_MENUS + " depth=" + depth
				+ " rootEntries=" + nodes.get(0).entries.length
				+ " skippedRootRows=" + skippedRootRows);
		}
		if (seen.put(menu, Boolean.TRUE) != null)
		{
			throw new IllegalArgumentException("menu identity cycle or reuse depth=" + depth
				+ " parentEntryIndex=" + parentEntryIndex);
		}
		MenuEntry[] entries = suppliedEntries == null ? menu.getMenuEntries() : suppliedEntries;
		boolean rootMenu = parentNode < 0;
		if (entries == null)
		{
			entries = new MenuEntry[0];
		}
		if ((rootMenu && entries.length == 0) || entries.length > MAX_ENTRIES)
		{
			throw new IllegalArgumentException("invalid menu entry count depth=" + depth
				+ " entries=" + entries.length);
		}
		totalEntries += entries.length;
		if (totalEntries > MAX_TOTAL_ENTRIES)
		{
			throw new IllegalArgumentException("menu total entry count limit exceeded total="
				+ totalEntries + " limit=" + MAX_TOTAL_ENTRIES + " depth=" + depth);
		}
		Rectangle bounds = new Rectangle(menu.getMenuX(), menu.getMenuY(),
			menu.getMenuWidth(), menu.getMenuHeight());
		if (bounds.width < 0 || bounds.height < 0
			|| (rootMenu && (bounds.width == 0 || bounds.height == 0))
			|| (!rootMenu && entries.length == 0 && bounds.width > 0 && bounds.height > 0))
		{
			throw new IllegalArgumentException("invalid menu bounds depth=" + depth
				+ " bounds=" + bounds + " entries=" + entries.length);
		}
		int index = nodes.size();
		boolean[] childFlags = new boolean[entries.length];
		boolean[] childOpensLeft = new boolean[entries.length];
		nodes.add(new Node(menu, entries, childFlags, childOpensLeft,
			bounds, parentNode, parentEntryIndex, header, headerFromTarget));
		if (parentNode >= 0)
		{
			candidateBounds.add(bounds);
			candidateParents.add(parentNode == 0 ? -1 : parentNode - 1);
		}
		for (int j = 0; j < entries.length; j++)
		{
			MenuEntry entry = entries[j];
			if (entry != null)
			{
				Menu child = entry.getSubMenu();
				if (child != null)
				{
					childFlags[j] = true;
					// Native drawing skips rows above the root scroll offset before
					// recursing into their children. Rows below the canvas are still
					// visited, so they must remain probe candidates.
					if (rootMenu && entries.length - 1 - j < skippedRootRows)
					{
						continue;
					}
					childOpensLeft[j] = child.getMenuWidth() > 0
						&& child.getMenuX() < menu.getMenuX();
					String target = entry.getTarget();
					boolean fromTarget = target != null && !target.trim().isEmpty();
					String contextualHeader = fromTarget ? target : entry.getOption();
					collect(child, null, index, j, contextualHeader, fromTarget, depth + 1, seen);
				}
			}
		}
	}

	static final class Node
	{
		final Menu menu;
		final MenuEntry[] entries;
		final boolean[] childFlags;
		final boolean[] childOpensLeft;
		final Rectangle bounds;
		final int parentNode;
		final int parentEntryIndex;
		final String header;
		/** The header repeats the opening entry's target, rather than its option. */
		final boolean headerFromTarget;

		private Node(Menu menu, MenuEntry[] entries, boolean[] childFlags,
			boolean[] childOpensLeft,
			Rectangle bounds, int parentNode, int parentEntryIndex, String header,
			boolean headerFromTarget)
		{
			this.menu = menu;
			this.entries = entries;
			this.childFlags = childFlags;
			this.childOpensLeft = childOpensLeft;
			this.bounds = bounds;
			this.parentNode = parentNode;
			this.parentEntryIndex = parentEntryIndex;
			this.header = header;
			this.headerFromTarget = headerFromTarget;
		}
	}
}
