package com.rightclickstyler;

import java.util.Objects;
import java.util.function.Consumer;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;

/** The public menu state that produced one prepared image. */
final class PreparedMenuState
{
	private final SubmenuTree tree;
	private final EntryState[][] selectedEntries;
	private final int[] selectedNodes;
	private final ScrollableMenuViewport rootViewport;
	private MouseLocation renderedMouse;

	private PreparedMenuState(SubmenuTree tree, int[] activeChain, Point mouse,
		ScrollableMenuViewport rootViewport)
	{
		this.tree = tree;
		this.rootViewport = rootViewport;
		this.selectedNodes = new int[activeChain.length + 1];
		this.selectedEntries = new EntryState[selectedNodes.length][];
		for (int depth = 0; depth < selectedNodes.length; depth++)
		{
			int nodeIndex = depth == 0 ? 0 : activeChain[depth - 1] + 1;
			selectedNodes[depth] = nodeIndex;
			MenuEntry[] entries = tree.nodes.get(nodeIndex).entries;
			EntryState[] snapshot = new EntryState[entries.length];
			for (int i = 0; i < entries.length; i++)
			{
				snapshot[i] = entries[i] == null ? null : new EntryState(entries[i]);
			}
			selectedEntries[depth] = snapshot;
		}
		renderedMouse = locate(mouse);
	}

	static PreparedMenuState capture(SubmenuTree tree, int[] activeChain, Point mouse)
	{
		return new PreparedMenuState(tree, activeChain, mouse, null);
	}

	static PreparedMenuState capture(SubmenuTree tree, int[] activeChain, Point mouse,
		ScrollableMenuViewport rootViewport)
	{
		return new PreparedMenuState(tree, activeChain, mouse, rootViewport);
	}

	boolean matches(Menu currentRoot, Point currentMouse)
	{
		return mismatchReason(currentRoot, currentMouse) == null;
	}

	String mismatchReason(Menu currentRoot, Point currentMouse)
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
			if (depth > 0)
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
		MouseLocation current = locate(currentMouse);
		if (!renderedMouse.selectedBranch && !renderedMouse.plainRoot)
		{
			return "mouse outside selected path " + renderedMouse;
		}
		return renderedMouse.sameHover(current) ? null
			: "mouse hover " + renderedMouse + " -> " + current;
	}

	/** A native-proved child remains valid while the pointer stays on its selected path. */
	boolean allowsMovement(Point from, Point to)
	{
		MouseLocation first = locate(from);
		MouseLocation second = locate(to);
		return ((first.selectedBranch || first.plainRoot) && first.sameHover(second))
			|| (first.selectedBranch && second.selectedBranch)
			|| (selectedNodes.length == 1 && first.plainRoot && second.plainRoot);
	}

	String movementReason(Point from, Point to)
	{
		return "mouse path " + locate(from) + " -> " + locate(to);
	}

	boolean canRetarget(Point mouse)
	{
		MouseLocation current = locate(mouse);
		return (renderedMouse.selectedBranch && current.selectedBranch)
			|| (selectedNodes.length == 1 && renderedMouse.plainRoot && current.plainRoot);
	}

	void setRenderedMouse(Point mouse)
	{
		renderedMouse = locate(mouse);
	}

	private MouseLocation locate(Point mouse)
	{
		if (mouse == null)
		{
			return MouseLocation.OUTSIDE;
		}
		// The deepest selected menu owns overlapping pixels.
		for (int depth = selectedNodes.length - 1; depth > 0; depth--)
		{
			SubmenuTree.Node node = tree.nodes.get(selectedNodes[depth]);
			int row = boundedRow(node.bounds, node.entries.length, mouse);
			if (row >= 0)
			{
				if (depth == selectedNodes.length - 1)
				{
					int entryIndex = node.entries.length - 1 - row;
					boolean opensUnprovedChild = node.entries[entryIndex] != null
						&& node.entries[entryIndex].getSubMenu() != null;
					return new MouseLocation(selectedNodes[depth], row,
						!opensUnprovedChild, false);
				}
				SubmenuTree.Node next = tree.nodes.get(selectedNodes[depth + 1]);
				if (node.entries.length - 1 - next.parentEntryIndex == row)
				{
					return new MouseLocation(selectedNodes[depth], row, true, false);
				}
				return new MouseLocation(selectedNodes[depth], row, false, false);
			}
		}
		SubmenuTree.Node root = tree.nodes.get(0);
		int row = rootViewport == null
			? boundedRow(root.bounds, root.entries.length, mouse)
			: rootViewport.hoverIndex(mouse);
		if (row < 0)
		{
			return MouseLocation.OUTSIDE;
		}
		if (selectedNodes.length > 1)
		{
			SubmenuTree.Node firstChild = tree.nodes.get(selectedNodes[1]);
			return new MouseLocation(0, row,
				root.entries.length - 1 - firstChild.parentEntryIndex == row, false);
		}
		int entryIndex = root.entries.length - 1 - row;
		boolean plain = entryIndex >= 0 && entryIndex < root.entries.length
			&& root.entries[entryIndex] != null && root.entries[entryIndex].getSubMenu() == null;
		return new MouseLocation(0, row, false, plain);
	}

	private static int boundedRow(java.awt.Rectangle bounds, int entries, Point mouse)
	{
		if (mouse.getX() < bounds.x || mouse.getX() >= bounds.x + bounds.width
			|| mouse.getY() < bounds.y + MenuRenderer.HEADER_HEIGHT
			|| mouse.getY() >= bounds.y + bounds.height)
		{
			return -1;
		}
		int row = (mouse.getY() - bounds.y - MenuRenderer.HEADER_HEIGHT) / MenuRenderer.ROW_HEIGHT;
		return row < entries ? row : -1;
	}

	private static final class MouseLocation
	{
		private static final MouseLocation OUTSIDE = new MouseLocation(-1, -1, false, false);
		private final int node;
		private final int row;
		private final boolean selectedBranch;
		private final boolean plainRoot;

		private MouseLocation(int node, int row, boolean selectedBranch, boolean plainRoot)
		{
			this.node = node;
			this.row = row;
			this.selectedBranch = selectedBranch;
			this.plainRoot = plainRoot;
		}

		private boolean sameHover(MouseLocation other)
		{
			return node == other.node && row == other.row;
		}

		@Override
		public String toString()
		{
			return node < 0 ? "outside" : "node=" + node + " row=" + row
				+ " selected=" + selectedBranch;
		}
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
