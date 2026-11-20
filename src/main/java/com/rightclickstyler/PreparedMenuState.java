package com.rightclickstyler;

import java.util.Objects;
import java.util.function.Consumer;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;

/**
 * The public menu structure that produced one prepared image.
 *
 * <p>The mouse is deliberately not part of this state. RuneLite's native submenu state only
 * changes in the client's per-cycle input step, never while a frame is being prepared or
 * drawn, so a pointer movement during preparation cannot invalidate the native probe. The
 * themed hover row comes from the single mouse sample taken when {@code BeforeMenuRender}
 * begins; a later movement is picked up by the next frame's probe.
 */
final class PreparedMenuState
{
	private final SubmenuTree tree;
	private final EntryState[][] selectedEntries;
	private final int[] selectedNodes;

	/** @param nodes the root (0) first, then each selected menu's node index once */
	private PreparedMenuState(SubmenuTree tree, int[] nodes)
	{
		this.tree = tree;
		this.selectedNodes = nodes;
		this.selectedEntries = new EntryState[selectedNodes.length][];
		for (int depth = 0; depth < selectedNodes.length; depth++)
		{
			MenuEntry[] entries = tree.nodes.get(selectedNodes[depth]).entries;
			EntryState[] snapshot = new EntryState[entries.length];
			for (int i = 0; i < entries.length; i++)
			{
				snapshot[i] = entries[i] == null ? null : new EntryState(entries[i]);
			}
			selectedEntries[depth] = snapshot;
		}
	}

	static PreparedMenuState capture(SubmenuTree tree, int[] activeChain)
	{
		return capture(tree, new int[][]{activeChain});
	}

	/**
	 * Every menu of every chain, so the frame drops if any of them changes. Used for visually
	 * equivalent alternatives, where any one of the chains may be the one native drew.
	 */
	static PreparedMenuState capture(SubmenuTree tree, int[][] chains)
	{
		int[] nodes = new int[tree.nodes.size()];
		boolean[] seen = new boolean[tree.nodes.size()];
		int count = 1;
		seen[0] = true;
		for (int[] chain : chains)
		{
			for (int candidate : chain)
			{
				int node = candidate + 1;
				if (!seen[node])
				{
					seen[node] = true;
					nodes[count++] = node;
				}
			}
		}
		return new PreparedMenuState(tree, java.util.Arrays.copyOf(nodes, count));
	}

	boolean matches(Menu currentRoot)
	{
		return mismatchReason(currentRoot) == null;
	}

	/**
	 * @return why the current public menu structure no longer matches the prepared image, or
	 * null when root identity, every node's geometry, the selected submenu links and the
	 * selected menus' entries are all unchanged.
	 */
	String mismatchReason(Menu currentRoot)
	{
		if (currentRoot != tree.root)
		{
			return "root identity";
		}
		for (SubmenuTree.Node node : tree.nodes)
		{
			Menu menu = node.menu;
			if (menu.getMenuX() != node.bounds.x || menu.getMenuY() != node.bounds.y
				|| menu.getMenuWidth() != node.bounds.width
				|| menu.getMenuHeight() != node.bounds.height)
			{
				return "geometry node=" + tree.nodes.indexOf(node);
			}
		}
		for (int depth = 0; depth < selectedNodes.length; depth++)
		{
			SubmenuTree.Node node = tree.nodes.get(selectedNodes[depth]);
			if (node.parentNode >= 0)
			{
				MenuEntry[] parentEntries = tree.nodes.get(node.parentNode).menu.getMenuEntries();
				if (parentEntries == null || node.parentEntryIndex >= parentEntries.length
					|| parentEntries[node.parentEntryIndex] == null
					|| parentEntries[node.parentEntryIndex].getSubMenu() != node.menu)
				{
					return "submenu link depth=" + depth + " entry=" + node.parentEntryIndex;
				}
			}
			MenuEntry[] current = node.menu.getMenuEntries();
			EntryState[] expected = selectedEntries[depth];
			if (current == null || current.length != expected.length)
			{
				return "entry count depth=" + depth;
			}
			for (int i = 0; i < current.length; i++)
			{
				if (expected[i] == null ? current[i] != null : current[i] == null)
				{
					return "entry presence depth=" + depth + " index=" + i;
				}
				if (expected[i] != null)
				{
					String field = expected[i].mismatchField(current[i]);
					if (field != null)
					{
						return "entry " + field + " depth=" + depth + " index=" + i;
					}
				}
			}
		}
		return null;
	}

	private static final class EntryState
	{
		private final String option;
		private final String target;
		private final Menu subMenu;
		private final MenuAction type;
		private final Consumer<MenuEntry> callback;
		private final int identifier;
		private final int param0;
		private final int param1;
		private final int worldViewId;
		private final int itemId;
		private final boolean forceLeftClick;
		private final boolean deprioritized;

		private EntryState(MenuEntry entry)
		{
			option = entry.getOption();
			target = entry.getTarget();
			subMenu = entry.getSubMenu();
			type = entry.getType();
			callback = entry.onClick();
			identifier = entry.getIdentifier();
			param0 = entry.getParam0();
			param1 = entry.getParam1();
			worldViewId = entry.getWorldViewId();
			itemId = entry.getItemId();
			forceLeftClick = entry.isForceLeftClick();
			deprioritized = entry.isDeprioritized();
		}

		private String mismatchField(MenuEntry entry)
		{
			if (!Objects.equals(option, entry.getOption()))
			{
				return "option";
			}
			if (!Objects.equals(target, entry.getTarget()))
			{
				return "target";
			}
			if (subMenu != entry.getSubMenu())
			{
				return "submenu";
			}
			if (type != entry.getType())
			{
				return "type";
			}
			if (callback != entry.onClick())
			{
				return "callback";
			}
			if (identifier != entry.getIdentifier())
			{
				return "identifier";
			}
			if (param0 != entry.getParam0())
			{
				return "param0";
			}
			if (param1 != entry.getParam1())
			{
				return "param1";
			}
			if (worldViewId != entry.getWorldViewId())
			{
				return "worldViewId";
			}
			if (itemId != entry.getItemId())
			{
				return "itemId";
			}
			if (forceLeftClick != entry.isForceLeftClick())
			{
				return "forceLeftClick";
			}
			if (deprioritized != entry.isDeprioritized())
			{
				return "deprioritized";
			}
			return null;
		}
	}
}
