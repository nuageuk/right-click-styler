package com.rightclickstyler;

import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;

@Slf4j
class MenuOverlay extends Overlay
{
	private static final int NO_ITEM = -1;
	private static final int EXACT_CONTRADICTION_LOG_LIMIT = 20;
	private static final int IMAGE_CACHE_MISMATCH_LOG_LIMIT = 10;
	private static final boolean SUBMENU_TRACE_ENABLED = Boolean.getBoolean("rightclickstyler.submenuTrace");
	private static final int SUBMENU_TRACE_LIMIT = 24;

	/**
	 * How the exact-coverage reading ("new") compares with the probe's previous resolver
	 * ("old") on one frame.
	 */
	enum ExactComparison
	{
		/** The exact reading did not run: the draw never reached the marked root. */
		NOT_EVALUATED,
		/** Both resolved the same chain. */
		AGREE,
		/** Both resolved a chain, and the chains differ. Never rendered. */
		CONTRADICTORY,
		/** The old resolver proved a chain; the exact reading did not resolve one. */
		OLD_RESOLVED_NEW_UNRESOLVED,
		/** The old resolver was ambiguous; the exact reading resolved a chain. */
		OLD_AMBIGUOUS_NEW_RESOLVED,
		/** The old resolver was ambiguous and the exact reading did not resolve a chain. */
		BOTH_UNRESOLVED,
		/** A buffer, restore or geometry check failed, so neither reading may be used. */
		OLD_FAILED
	}

	enum SubmenuOutcome
	{
		READY_ROOT_ONLY,
		READY_WITH_CHILD,
		READY_WITH_NATIVE_DETACHED_CHILD,
		/**
		 * The pixels fit several chains (such as two siblings sharing one rectangle), and every
		 * one of them renders identically, so the frame is themed without knowing which one
		 * native drew.
		 */
		READY_WITH_EQUIVALENT_CHILD,
		/**
		 * The pixels fit several one-menu chains sharing one rectangle, and the native draw's
		 * own pixels in that rectangle exclude all but one of them ({@link NativeChildImages}).
		 */
		READY_WITH_NATIVE_IDENTIFIED_CHILD,
		FALLBACK_AMBIGUOUS,
		FALLBACK_INVALID_BOUNDS,
		FALLBACK_BUFFER_MISMATCH,
		FALLBACK_NATIVE_DRAW_MISSED,
		FALLBACK_RESTORE_FAILURE,
		FALLBACK_GEOMETRY_CHANGED,
		FALLBACK_TREE_DEPTH,
		FALLBACK_TREE_COUNT,
		FALLBACK_TREE_CYCLE,
		FALLBACK_COMPLEXITY,
		FALLBACK_RENDER_EXCEPTION,
		FALLBACK_PROBE_EXCEPTION,
		FALLBACK_SCROLLABLE,
		FALLBACK_TRANSIENT,
		/** The exact reading was unevidenced, unexplained or failed. */
		FALLBACK_EXACT_UNRESOLVED,
		/** The two resolvers proved different chains. Neither is drawn. */
		FALLBACK_EXACT_CONTRADICTION,
		FALLBACK_OTHER
	}

	private final Client client;
	private final RightClickStylerConfig config;
	private final ThemeManager themeManager;
	private final MenuRenderer renderer = new MenuRenderer();
	private final NativeSubmenuProbe probe = new NativeSubmenuProbe();
	/** Native images of child menus this menu opening proved; cleared with each opening. */
	private final NativeChildImages nativeChildImages = new NativeChildImages();
	private final long[] exactComparisons = new long[ExactComparison.values().length];
	private long exactContradictionLogs;
	private long exactContradictionSuppressed;
	private ExactComparison lastExactComparison = ExactComparison.NOT_EVALUATED;
	private SubmenuOutcome lastOutcome;
	private final long[] submenuOutcomes = new long[SubmenuOutcome.values().length];
	/**
	 * Reuse the themed image when every rendering input is unchanged. The probe and every
	 * check still run each frame. Tests can turn the cache off, or re-render every Nth hit and
	 * compare pixels; production never verifies.
	 */
	private boolean imageCacheEnabled = true;
	private int imageCacheVerifyEvery;
	private final SubmenuImageCache imageCache = new SubmenuImageCache();
	private SubmenuImageCache.Miss lastImageMiss;
	private final long[] imageMisses = new long[SubmenuImageCache.Miss.values().length];
	private long imageVerifyChecks;
	private long imageVerifyMismatchTotal;
	private BufferedImage verifyImage;
	private int[] verifyExpected = new int[0];
	private int[] verifyActual = new int[0];
	private final List<MenuRenderer.Row> rows = new ArrayList<>();
	private final List<MenuRenderer.Row> rowPool = new ArrayList<>();
	private final Rectangle bounds = new Rectangle();
	private final List<RowBuffer> submenuRows = new ArrayList<>();
	private BufferedImage submenuImage;
	private int preparedX;
	private int preparedY;
	private Menu preparedRoot;
	private PreparedMenuState preparedState;
	private boolean preparedScrollable;
	private int preparedScroll;
	private int preparedFirstRow;
	/**
	 * What the prepared image was drawn from, kept so that a structural change after the event
	 * is consumed can be proven not to change the image ({@link #imageMatchesLiveMenus}).
	 */
	private SubmenuTree preparedTree;
	/** The renderer inputs of every drawn menu, root first. */
	private SubmenuImageCache.Menu[] preparedMenus;
	/** Every chain the image stands for, or null when none can be re-proven at the final draw. */
	private int[][] preparedChains;
	private ScrollableMenuViewport preparedViewport;
	private Point preparedMouse;
	private boolean prepared;
	private boolean externallyConsumed;
	private long lastFailureLog;
	private long submenuBlits;
	/** Final draws whose live structure changed after consumption without changing the image. */
	private long submenuEquivalentBlits;
	private long submenuClosedDrops;
	private long submenuChangedDrops;
	/**
	 * A consumed frame failed its final draw in this menu opening. Submenus stay native, without
	 * being probed, for the rest of the opening; {@link #menuOpened} ends it.
	 */
	private boolean submenuBlitFailed;
	/** A consumed frame was themed in this menu opening. */
	private boolean openingBlitted;
	/** Consecutive menu openings that ended with a failed final draw. */
	private int failedOpenings;
	/**
	 * A failure that recurs on every opening (a draw that always fails, or another plugin that
	 * changes the menu mid-frame each time) would otherwise blank one frame per opening: after
	 * this many consecutive failed openings, submenus stay native until the plugin restarts.
	 */
	static final int FAILED_OPENINGS_LIMIT = 3;
	private boolean submenuDisabled;
	private int submenuTraceLogs;
	private final boolean[] tracedOutcomes = new boolean[SubmenuOutcome.values().length];

	/**
	 * Set after the first render failure. The plugin consumes {@code BeforeMenuRender}, so a render
	 * that throws would leave the menu invisible; once this is set we stop consuming and the vanilla
	 * menu is drawn for the rest of the session. Volatile because the plugin resets it from startUp.
	 */
	private volatile boolean failed;

	@Inject
	private MenuOverlay(Client client, RightClickStylerConfig config, ThemeManager themeManager)
	{
		this.client = client;
		this.config = config;
		this.themeManager = themeManager;
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPosition(OverlayPosition.DYNAMIC);
	}

	/** Tests only: production always uses the cache and never verifies a hit. */
	synchronized void setImageCache(boolean enabled, int verifyEvery)
	{
		imageCacheEnabled = enabled;
		imageCacheVerifyEvery = verifyEvery;
		imageCache.invalidate();
	}

	/** From startUp: a restart ends every failure state, except a failed opening still on screen. */
	void resetFailure()
	{
		failed = false;
		synchronized (this)
		{
			clearPrepared();
			imageCache.invalidate();
			nativeChildImages.clear();
			// No MenuOpened was seen for an open menu, so it may be the failed opening: keep it
			// native until the next opening. Reads one client flag; no rendering state.
			submenuBlitFailed = submenuBlitFailed && client.isMenuOpen();
			openingBlitted = false;
			failedOpenings = 0;
			submenuDisabled = false;
		}
	}

	/**
	 * RuneLite posts MenuOpened from the client's root-menu opening, before the menu is marked
	 * open, on every opening; native submenu changes do not pass through it. It therefore ends
	 * the previous opening, however it closed, before anything of the new one is probed or drawn.
	 * RuneLite reuses the root Menu object, so the opening must also end any prepared frame.
	 */
	synchronized void menuOpened()
	{
		clearPrepared();
		nativeChildImages.clear();
		if (submenuBlitFailed)
		{
			// Nothing drawn for the failed opening is reused: the next image is rendered afresh.
			imageCache.invalidate();
			if (++failedOpenings >= FAILED_OPENINGS_LIMIT)
			{
				submenuDisabled = true;
			}
		}
		else if (openingBlitted)
		{
			failedOpenings = 0;
		}
		submenuBlitFailed = false;
		openingBlitted = false;
	}

	synchronized void discardPreparedAtEventStart()
	{
		clearPrepared();
	}

	synchronized void setExternallyConsumed(boolean consumed)
	{
		externallyConsumed = consumed;
	}

	private void recordOutcome(SubmenuOutcome outcome)
	{
		recordOutcome(outcome, "");
	}

	private void recordOutcome(SubmenuOutcome outcome, String detail)
	{
		if (SUBMENU_TRACE_ENABLED && outcome.name().startsWith("FALLBACK")
			&& !tracedOutcomes[outcome.ordinal()])
		{
			tracedOutcomes[outcome.ordinal()] = true;
			traceOutcome(outcome.name(), detail);
		}
		lastOutcome = outcome;
		submenuOutcomes[outcome.ordinal()]++;
	}

	/** Bounded opt-in outcome logging without inspecting menu entries on each frame. */
	private void traceOutcome(String state, String detail)
	{
		if (!SUBMENU_TRACE_ENABLED || submenuTraceLogs >= SUBMENU_TRACE_LIMIT)
		{
			return;
		}
		submenuTraceLogs++;
		log.info("RCS_SUBMENU outcome={} detail={}", state, Limits.truncate(detail == null ? "" : detail, 120));
	}

	private void traceFinalDrop(String detail)
	{
		traceOutcome("FINAL_DROP", detail);
	}

	/**
	 * Prepares the themed image for a menu with submenus or a scrollable root.
	 *
	 * @param frameMouse the one mouse sample taken when {@code BeforeMenuRender} began. It is the
	 * only mouse position used for this frame: the native probe proves which submenus are open
	 * in this client cycle, and that state cannot change until the next cycle, so the live
	 * pointer is never re-read to revalidate the frame.
	 */
	synchronized boolean prepareSubmenuFrame(Menu root, MenuEntry[] rootEntries, Point frameMouse)
	{
		prepared = false;
		preparedRoot = null;
		preparedState = null;
		if (!client.isMenuOpen())
		{
			recordOutcome(SubmenuOutcome.FALLBACK_TRANSIENT);
			return false;
		}
		boolean scrollable = client.isMenuScrollable();
		int scroll = scrollable ? client.getMenuScroll() : 0;
		if (submenuBlitFailed || submenuDisabled)
		{
			recordOutcome(SubmenuOutcome.FALLBACK_RENDER_EXCEPTION);
			return false;
		}
		boolean preparingTheme = false;
		try
		{
			int canvasWidth = client.getCanvasWidth();
			int canvasHeight = client.getCanvasHeight();
			if (canvasWidth <= 0 || canvasHeight <= 0
				|| (long) canvasWidth * canvasHeight > 8_388_608L)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_INVALID_BOUNDS);
				return false;
			}
			ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
				new Rectangle(root.getMenuX(), root.getMenuY(), root.getMenuWidth(), root.getMenuHeight()),
				rootEntries.length,
				canvasWidth, canvasHeight, scrollable, scroll);
			if (viewport == null)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_SCROLLABLE);
				return false;
			}
			SubmenuTree tree = SubmenuTree.capture(root, rootEntries,
				scrollable ? viewport.firstRow : 0);
			NativeSubmenuProbe.Result result = probe.probe(client, tree);
			if (result.state == NativeSubmenuProbe.State.GEOMETRY_CHANGED)
			{
				// Native drawing can update even a previously nonzero child rectangle.
				// Re-sample once against the current positions.
				tree = SubmenuTree.capture(root, root.getMenuEntries(),
					scrollable ? viewport.firstRow : 0);
				result = probe.probe(client, tree);
				viewport = ScrollableMenuViewport.capture(tree.nodes.get(0).bounds,
					tree.nodes.get(0).entries.length, canvasWidth, canvasHeight,
					scrollable, scroll);
			}
			// Pure classification: the rendering decision below depends on it.
			ExactComparison comparison = classifyExact(result);
			exactRecord(result, tree, comparison);
			if (viewport == null || client.isMenuScrollable() != scrollable
				|| (scrollable && client.getMenuScroll() != scroll))
			{
				recordOutcome(SubmenuOutcome.FALLBACK_SCROLLABLE);
				return false;
			}
			// The exact reading decides. Anything but one uniquely resolved chain on a
			// verified framebuffer stays native: no guess, no forced selection.
			SubmenuOutcome rejection = authoritativeRejection(result, tree);
			// Ambiguous pixels: theme only when every chain they fit renders identically.
			int[][] equivalent = rejection == SubmenuOutcome.FALLBACK_AMBIGUOUS
				? visuallyEquivalentAlternatives(result, tree, viewport, frameMouse, scroll, null) : null;
			// Otherwise, only the native draw's own pixels may tell the alternatives apart.
			int[] identified = null;
			if (rejection == SubmenuOutcome.FALLBACK_AMBIGUOUS && equivalent == null)
			{
				identified = nativeChildImages.identify(result, tree, null);
			}
			if (rejection == null && result.exact.chain.length == 1)
			{
				nativeChildImages.remember(result, tree, result.exact.chain[0]);
			}
			else if (identified != null)
			{
				nativeChildImages.remember(result, tree, identified[0]);
			}
			if (rejection != null && equivalent == null && identified == null)
			{
				recordOutcome(rejection, result.reason);
				return false;
			}
			int[] chain = equivalent != null ? equivalent[0] : identified != null ? identified : result.exact.chain;
			ScrollChildValidation childValidation = ScrollChildValidation.ATTACHED;
			for (int i = 0; scrollable && i < (equivalent != null ? equivalent.length : 1); i++)
			{
				// An ambiguous frame proves no single child, so a detached one is rejected here.
				childValidation = validateScrolledChild(tree, equivalent != null ? equivalent[i] : chain,
					viewport, canvasHeight, result);
				if (childValidation.reason != null)
				{
					recordOutcome(SubmenuOutcome.FALLBACK_SCROLLABLE);
					return false;
				}
			}
			Rectangle region = new Rectangle(tree.nodes.get(0).bounds);
			for (int candidate : chain)
			{
				region.add(tree.nodes.get(candidate + 1).bounds);
			}
			region.grow(3, 3);
			region = region.intersection(new Rectangle(0, 0, canvasWidth, canvasHeight));
			if (region.width <= 0 || region.height <= 0)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_INVALID_BOUNDS);
				return false;
			}
			if (submenuImage == null || submenuImage.getWidth() != region.width
				|| submenuImage.getHeight() != region.height)
			{
				submenuImage = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB);
				imageCache.invalidate();
			}
			// Every alternative's nodes, so a change to any of them before the blit drops the frame.
			PreparedMenuState state = equivalent != null ? PreparedMenuState.capture(tree, equivalent)
				: identified != null ? PreparedMenuState.capture(tree, result.exact.alternatives)
				: PreparedMenuState.capture(tree, chain);
			String mismatch = state.mismatchReason(client.getMenu());
			if (mismatch != null)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_TRANSIENT, mismatch);
				return false;
			}
			preparingTheme = true;
			Theme theme = themeManager.effectiveTheme();
			Player player = client.getLocalPlayer();
			int localLevel = player == null ? 0 : player.getCombatLevel();
			SubmenuImageCache.Menu[] drawn = renderOrReuseSubmenuImage(tree, chain, viewport, region,
				frameMouse, scroll, theme, localLevel);
			// Structural state only: the frame's mouse sample is fixed, so pointer
			// movement since the sample is not a reason to discard native proof.
			boolean changed = !client.isMenuOpen()
				|| client.isMenuScrollable() != scrollable
				|| (scrollable && client.getMenuScroll() != scroll)
				|| state.mismatchReason(client.getMenu()) != null;
			if (changed)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_TRANSIENT, "menu/scroll/entry changed after image render");
				return false;
			}
			preparedX = region.x;
			preparedY = region.y;
			preparedRoot = tree.root;
			preparedState = state;
			preparedScrollable = scrollable;
			preparedScroll = scroll;
			preparedFirstRow = scrollable ? viewport.firstRow : 0;
			preparedTree = tree;
			preparedMenus = drawn;
			// An identified child was told apart from its alternatives by native pixels that no
			// longer exist after consumption, so only a proven or equivalent chain is re-checked.
			preparedChains = equivalent != null ? equivalent : identified != null ? null : new int[][]{chain};
			preparedViewport = viewport;
			preparedMouse = frameMouse;
			prepared = true;
			SubmenuOutcome ready = equivalent != null ? SubmenuOutcome.READY_WITH_EQUIVALENT_CHILD
				: identified != null ? SubmenuOutcome.READY_WITH_NATIVE_IDENTIFIED_CHILD
				: chain.length == 0 ? SubmenuOutcome.READY_ROOT_ONLY
				: childValidation.detached ? SubmenuOutcome.READY_WITH_NATIVE_DETACHED_CHILD
				: SubmenuOutcome.READY_WITH_CHILD;
			recordOutcome(ready);
			return true;
		}
		catch (IllegalArgumentException e)
		{
			clearPrepared();
			recordOutcome(preparingTheme ? SubmenuOutcome.FALLBACK_RENDER_EXCEPTION
				: treeFailureOutcome(e.getMessage()));
			return false;
		}
		catch (RuntimeException e)
		{
			clearPrepared();
			recordOutcome(preparingTheme
				? SubmenuOutcome.FALLBACK_RENDER_EXCEPTION : SubmenuOutcome.FALLBACK_PROBE_EXCEPTION,
				e.getClass().getSimpleName() + ": " + e.getMessage());
			long now = System.nanoTime();
			if (now - lastFailureLog >= 5_000_000_000L)
			{
				lastFailureLog = now;
				log.warn("Submenu preparation failed; native menu retained", e);
			}
			return false;
		}
	}

	/**
	 * Compares the exact reading ("new") with the probe's previous resolver ("old"). Pure and
	 * total over every probe result, so the rendering decision can rely on it. An exact
	 * ERROR counts as unresolved: it never proves a chain.
	 */
	static ExactComparison classifyExact(NativeSubmenuProbe.Result result)
	{
		ExactCoverageResolver.Outcome exact = result.exact;
		if (exact == null || exact.state == ExactCoverageResolver.State.NOT_EVALUATED)
		{
			return ExactComparison.NOT_EVALUATED;
		}
		if (!result.framebufferVerified)
		{
			return ExactComparison.OLD_FAILED;
		}
		boolean resolved = exact.state == ExactCoverageResolver.State.RESOLVED;
		if (result.state == NativeSubmenuProbe.State.NO_CHILD
			|| result.state == NativeSubmenuProbe.State.ACTIVE)
		{
			return !resolved ? ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED
				: Arrays.equals(exact.chain, result.activeChain) ? ExactComparison.AGREE
				: ExactComparison.CONTRADICTORY;
		}
		if (result.state == NativeSubmenuProbe.State.AMBIGUOUS)
		{
			return resolved ? ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED : ExactComparison.BOTH_UNRESOLVED;
		}
		return ExactComparison.OLD_FAILED;
	}

	/**
	 * The rendering gate: null only for one uniquely resolved, well-formed exact chain on
	 * a verified framebuffer that the previous resolver does not contradict. Every later
	 * structural, scroll and lifecycle check still applies to the chain it lets through.
	 */
	static SubmenuOutcome authoritativeRejection(NativeSubmenuProbe.Result result, SubmenuTree tree)
	{
		if (!result.framebufferVerified)
		{
			// Buffer, restore and geometry failures keep their existing outcomes.
			return failureOutcome(result);
		}
		ExactCoverageResolver.Outcome exact = result.exact;
		if (exact == null || exact.state == ExactCoverageResolver.State.AMBIGUOUS)
		{
			return SubmenuOutcome.FALLBACK_AMBIGUOUS;
		}
		if (exact.state != ExactCoverageResolver.State.RESOLVED || exact.explainingChains != 1
			|| !wellFormedChain(exact.chain, tree))
		{
			return SubmenuOutcome.FALLBACK_EXACT_UNRESOLVED;
		}
		if (classifyExact(result) == ExactComparison.CONTRADICTORY)
		{
			return SubmenuOutcome.FALLBACK_EXACT_CONTRADICTION;
		}
		return null;
	}

	/** Each member is a real candidate whose parent is the previous member (the root first). */
	private static boolean wellFormedChain(int[] chain, SubmenuTree tree)
	{
		int parent = -1;
		for (int candidate : chain)
		{
			if (candidate < 0 || candidate >= tree.candidateParents.size()
				|| tree.candidateParents.get(candidate) != parent)
			{
				return false;
			}
			parent = candidate;
		}
		return true;
	}

	/**
	 * The chains an ambiguous frame fits, when themeing it needs no choice between them: null
	 * unless the exact reading listed every explaining chain on a verified framebuffer, each is
	 * well formed, any chain the previous resolver proved is among them, and every one gives
	 * the renderer equal inputs for every menu it draws (bounds, header and its colour kind,
	 * every row's option, target, kind and arrow, and hovered row). Callbacks, entry identity
	 * and which entry opened a child are not rendering inputs, so siblings that differ only
	 * there theme identically. Menu Entry Swapper's left-click and shift-click submenus usually
	 * do not: each leaves out the op currently set for its own mode, so their rows differ even
	 * though their row counts, and so their rectangles at a clamped edge, are equal.
	 *
	 * <p>The root is the same node in every chain and its hover comes from the frame's mouse
	 * sample alone, so no root row is highlighted because of which child native drew.
	 */
	static int[][] visuallyEquivalentAlternatives(NativeSubmenuProbe.Result result, SubmenuTree tree,
		ScrollableMenuViewport viewport, Point mouse, int scroll)
	{
		return visuallyEquivalentAlternatives(result, tree, viewport, mouse, scroll, null);
	}

	/** @param why diagnostics only: receives the reason when the result is null; may be null */
	static int[][] visuallyEquivalentAlternatives(NativeSubmenuProbe.Result result, SubmenuTree tree,
		ScrollableMenuViewport viewport, Point mouse, int scroll, StringBuilder why)
	{
		ExactCoverageResolver.Outcome exact = result.exact;
		if (!result.framebufferVerified || exact == null
			|| exact.state != ExactCoverageResolver.State.AMBIGUOUS || exact.alternatives.length < 2
			|| exact.alternatives.length != exact.explainingChains)
		{
			if (why != null)
			{
				why.append(!result.framebufferVerified ? "framebuffer unverified"
					: exact == null ? "no exact reading"
					: exact.state != ExactCoverageResolver.State.AMBIGUOUS ? "exact " + exact.state
					: exact.hiddenCandidate >= 0 ? "hidden-grandchild guard: candidate " + exact.hiddenCandidate
						+ " could be drawn unseen beneath an explaining chain"
					: "alternatives incomplete " + exact.alternatives.length + "/" + exact.explainingChains);
			}
			return null;
		}
		int[][] alternatives = exact.alternatives;
		boolean previousProved = false;
		for (int[] alternative : alternatives)
		{
			if (!wellFormedChain(alternative, tree))
			{
				if (why != null)
				{
					why.append("malformed chain ").append(Arrays.toString(alternative));
				}
				return null;
			}
			previousProved |= Arrays.equals(alternative, result.activeChain);
		}
		if ((result.state == NativeSubmenuProbe.State.NO_CHILD
			|| result.state == NativeSubmenuProbe.State.ACTIVE) && !previousProved)
		{
			if (why != null)
			{
				why.append("previous resolver proved ").append(Arrays.toString(result.activeChain))
					.append(", not an alternative");
			}
			return null; // the previous resolver proved a chain the pixels do not fit
		}
		List<MenuRenderer.Row> rootRows = new ArrayList<>();
		SubmenuImageCache.Menu rootMenu = rootMenu(tree, viewport, mouse, scroll, rootRows);
		SubmenuImageCache.Menu[] first = drawnMenus(tree, alternatives[0], rootMenu, rootRows, mouse);
		for (int i = 1; i < alternatives.length; i++)
		{
			SubmenuImageCache.Menu[] other = drawnMenus(tree, alternatives[i], rootMenu, rootRows, mouse);
			if (SubmenuImageCache.menusDifference(first, other) != SubmenuImageCache.Miss.HIT)
			{
				if (why != null)
				{
					why.append("chains ").append(Arrays.toString(alternatives[0])).append(" and ")
						.append(Arrays.toString(alternatives[i])).append(" differ: ")
						.append(SubmenuImageCache.describeDifference(first, other));
				}
				return null;
			}
		}
		return alternatives;
	}

	/** The root's renderer inputs, filled as {@link #renderOrReuseSubmenuImage} fills them; fills {@code rootRows}. */
	private static SubmenuImageCache.Menu rootMenu(SubmenuTree tree, ScrollableMenuViewport viewport,
		Point mouse, int scroll, List<MenuRenderer.Row> rootRows)
	{
		SubmenuTree.Node root = tree.nodes.get(0);
		MenuRenderer.TargetKind rootKind = fillMenuRows(root, null, rootRows, new ArrayList<>());
		return SubmenuImageCache.menu(root.bounds, root.header, rootKind,
			scroll, viewport.hoverIndex(mouse), rootRows);
	}

	/**
	 * Whether the prepared image is still exactly the image preparation would draw for the live
	 * menus. Called only after the event was consumed and the strict final check failed, so the
	 * alternative is a frame with no menu at all.
	 *
	 * <p>True only when the live hierarchy has the prepared nodes (the same menu objects, opened
	 * from the same entries, with the same geometry) and, for every chain the image stands for,
	 * the renderer would now receive inputs equal to those it drew from. Native submenu state
	 * changes only in the client's per-cycle input step, and its geometry and links are
	 * unchanged, so the chain the probe proved is still the one open. What changed (for example
	 * a click callback, or the entries of a menu that is not drawn) is not a rendering input, so
	 * the image is pixel-identical to a fresh render of the live state and every native hitbox is
	 * where the image shows it. Any visible difference returns false; nothing is re-rendered.
	 */
	private boolean imageMatchesLiveMenus(Menu currentRoot)
	{
		if (preparedTree == null || preparedMenus == null || preparedChains == null)
		{
			return false;
		}
		SubmenuTree live;
		try
		{
			live = SubmenuTree.capture(currentRoot, currentRoot.getMenuEntries(), preparedFirstRow);
		}
		catch (IllegalArgumentException e)
		{
			return false; // the live hierarchy is outside the supported limits
		}
		if (!sameNodes(preparedTree, live))
		{
			return false;
		}
		for (int[] chain : preparedChains)
		{
			List<MenuRenderer.Row> rootRows = new ArrayList<>();
			SubmenuImageCache.Menu root = rootMenu(live, preparedViewport, preparedMouse, preparedScroll, rootRows);
			if (SubmenuImageCache.menusDifference(preparedMenus,
				drawnMenus(live, chain, root, rootRows, preparedMouse)) != SubmenuImageCache.Miss.HIT)
			{
				return false;
			}
		}
		return true;
	}

	/** The same menu objects in the same order, opened from the same entries, at the same bounds. */
	private static boolean sameNodes(SubmenuTree prepared, SubmenuTree live)
	{
		if (prepared.nodes.size() != live.nodes.size())
		{
			return false;
		}
		for (int i = 0; i < prepared.nodes.size(); i++)
		{
			SubmenuTree.Node a = prepared.nodes.get(i);
			SubmenuTree.Node b = live.nodes.get(i);
			if (a.menu != b.menu || a.parentNode != b.parentNode
				|| a.parentEntryIndex != b.parentEntryIndex || !a.bounds.equals(b.bounds))
			{
				return false;
			}
		}
		return true;
	}

	/** The renderer inputs of one chain, filled exactly as {@link #renderOrReuseSubmenuImage} fills them. */
	private static SubmenuImageCache.Menu[] drawnMenus(SubmenuTree tree, int[] chain,
		SubmenuImageCache.Menu rootMenu, List<MenuRenderer.Row> rootRows, Point mouse)
	{
		SubmenuImageCache.Menu[] menus = new SubmenuImageCache.Menu[chain.length + 1];
		menus[0] = rootMenu;
		List<MenuRenderer.Row> parentRows = rootRows;
		for (int depth = 1; depth <= chain.length; depth++)
		{
			SubmenuTree.Node node = menuNode(tree, chain, depth);
			MenuRenderer.Row parentRow = openingRow(tree, node, menuNode(tree, chain, depth - 1), parentRows);
			List<MenuRenderer.Row> rows = new ArrayList<>();
			MenuRenderer.TargetKind headerKind = fillMenuRows(node, parentRow, rows, new ArrayList<>());
			menus[depth] = SubmenuImageCache.menu(node.bounds, node.header, headerKind, 0,
				hoverIndex(node.bounds, rows.size(), mouse), rows);
			parentRows = rows;
		}
		return menus;
	}

	/** Bookkeeping only: nothing here reaches the rendering decision. */
	private void exactRecord(NativeSubmenuProbe.Result result, SubmenuTree tree, ExactComparison comparison)
	{
		lastExactComparison = comparison;
		exactComparisons[comparison.ordinal()]++;
		if (comparison == ExactComparison.CONTRADICTORY)
		{
			logContradiction(result, tree);
		}
	}

	/** Both resolvers proved a chain and they differ: logged a bounded number of times per session. */
	private void logContradiction(NativeSubmenuProbe.Result result, SubmenuTree tree)
	{
		if (exactContradictionLogs++ >= EXACT_CONTRADICTION_LOG_LIMIT)
		{
			exactContradictionSuppressed++;
			return;
		}
		try
		{
			log.warn("Submenu exact-coverage contradiction; native menu kept. probe={} probeChain={} exact={} exactChain={} covered={} scroll={} root={} canvas={}x{} candidates={} bounds={} parents={}",
				result.state + ": " + result.reason, chainBounds(tree, result.activeChain), result.exact,
				chainBounds(tree, result.exact.chain), chainBounds(tree, result.exact.covered),
				client.getMenuScroll(), tree.nodes.get(0).bounds, client.getCanvasWidth(), client.getCanvasHeight(),
				tree.candidateBounds.size(), tree.candidateBounds, tree.candidateParents);
		}
		catch (RuntimeException ignored)
		{
			// Logging must never affect the rendering decision.
		}
	}

	private static String chainBounds(SubmenuTree tree, int[] chain)
	{
		StringBuilder text = new StringBuilder("[");
		for (int candidate : chain)
		{
			if (candidate >= 0 && candidate < tree.candidateBounds.size())
			{
				Rectangle bounds = tree.candidateBounds.get(candidate);
				text.append(text.length() > 1 ? " " : "").append(candidate).append('=')
					.append(bounds.x).append(',').append(bounds.y).append(',')
					.append(bounds.width).append('x').append(bounds.height);
			}
		}
		return text.append(']').toString();
	}

	/**
	 * Fills the rows of every drawn menu, then renders them into {@link #submenuImage}, or
	 * keeps the image already there when the cache is enabled and every rendering input equals
	 * the inputs it was rendered from.
	 *
	 * @return the renderer inputs of every drawn menu, root first, as the image now holds them
	 */
	private SubmenuImageCache.Menu[] renderOrReuseSubmenuImage(SubmenuTree tree, int[] activeChain,
		ScrollableMenuViewport viewport, Rectangle region, Point mouse, int scroll,
		Theme theme, int localLevel)
	{
		int menus = activeChain.length + 1;
		int[] hovered = new int[menus];
		for (int depth = 0; depth < menus; depth++)
		{
			SubmenuTree.Node node = menuNode(tree, activeChain, depth);
			RowBuffer buffer = submenuRowBuffer(depth);
			// Shallower depths are filled first, so the opening row already holds this frame's state.
			MenuRenderer.Row parentRow = depth == 0 ? null : openingRow(tree, node,
				menuNode(tree, activeChain, depth - 1), submenuRowBuffer(depth - 1).rows);
			buffer.headerKind = fillMenuRows(node, parentRow, buffer.rows, buffer.pool);
			hovered[depth] = depth == 0
				? viewport.hoverIndex(mouse)
				: hoverIndex(node.bounds, buffer.rows.size(), mouse);
		}
		SubmenuImageCache.Menu[] drawn = new SubmenuImageCache.Menu[menus];
		for (int depth = 0; depth < menus; depth++)
		{
			SubmenuTree.Node node = menuNode(tree, activeChain, depth);
			RowBuffer buffer = submenuRowBuffer(depth);
			drawn[depth] = SubmenuImageCache.menu(node.bounds, node.header, buffer.headerKind,
				depth == 0 ? scroll : 0, hovered[depth], buffer.rows);
		}
		if (!imageCacheEnabled)
		{
			drawSubmenuImage(submenuImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
			return drawn;
		}
		SubmenuImageCache.Key key = SubmenuImageCache.capture(theme, config, client.getModIcons(),
			localLevel, region, drawn);
		SubmenuImageCache.Miss miss = imageCache.lookup(key);
		lastImageMiss = miss;
		imageMisses[miss.ordinal()]++;
		if (miss == SubmenuImageCache.Miss.HIT)
		{
			if (imageCacheVerifyEvery > 0 && imageMisses[miss.ordinal()] % imageCacheVerifyEvery == 0
				&& !verifyReusedImage(tree, activeChain, region, hovered, scroll, theme, localLevel))
			{
				// A rendering input is missing from the key. Never show the stale image.
				imageCache.invalidate();
				drawSubmenuImage(submenuImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
				imageCache.rendered(key);
			}
			return drawn;
		}
		// Invalid until the render completes, so a render that throws can never be reused.
		imageCache.invalidate();
		drawSubmenuImage(submenuImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
		if (key != null)
		{
			imageCache.rendered(key);
		}
		return drawn;
	}

	private static SubmenuTree.Node menuNode(SubmenuTree tree, int[] activeChain, int depth)
	{
		return tree.nodes.get(depth == 0 ? 0 : activeChain[depth - 1] + 1);
	}

	/** Draws the rows already filled for each depth. */
	private void drawSubmenuImage(BufferedImage image, SubmenuTree tree, int[] activeChain,
		Rectangle region, int[] hovered, int scroll, Theme theme, int localLevel)
	{
		Graphics2D g = image.createGraphics();
		try
		{
			g.setComposite(AlphaComposite.Clear);
			g.fillRect(0, 0, region.width, region.height);
			g.setComposite(AlphaComposite.SrcOver);
			g.translate(-region.x, -region.y);
			for (int depth = 0; depth <= activeChain.length; depth++)
			{
				SubmenuTree.Node node = menuNode(tree, activeChain, depth);
				RowBuffer buffer = submenuRowBuffer(depth);
				renderer.render(g, client, theme, config, buffer.rows, node.bounds,
					hovered[depth], localLevel, node.header, buffer.headerKind, depth == 0 ? scroll : 0);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	/** Renders the same inputs into a scratch image and compares every pixel. */
	private boolean verifyReusedImage(SubmenuTree tree, int[] activeChain, Rectangle region,
		int[] hovered, int scroll, Theme theme, int localLevel)
	{
		int width = region.width;
		int height = region.height;
		if (verifyImage == null || verifyImage.getWidth() != width || verifyImage.getHeight() != height)
		{
			verifyImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		}
		drawSubmenuImage(verifyImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
		if (verifyExpected.length != width * height)
		{
			verifyExpected = new int[width * height];
			verifyActual = new int[width * height];
		}
		// getRGB copies through the raster, so the displayed image stays GPU-accelerable.
		verifyImage.getRGB(0, 0, width, height, verifyExpected, 0, width);
		submenuImage.getRGB(0, 0, width, height, verifyActual, 0, width);
		imageVerifyChecks++;
		if (Arrays.equals(verifyExpected, verifyActual))
		{
			return true;
		}
		if (imageVerifyMismatchTotal++ < IMAGE_CACHE_MISMATCH_LOG_LIMIT)
		{
			log.warn("Submenu image cache mismatch region={} chain={}: a rendering input is missing from the cache key; re-rendered",
				region, Arrays.toString(activeChain));
		}
		return false;
	}

	private static SubmenuOutcome treeFailureOutcome(String reason)
	{
		if (reason == null)
		{
			return SubmenuOutcome.FALLBACK_OTHER;
		}
		if (reason.startsWith("menu depth limit"))
		{
			return SubmenuOutcome.FALLBACK_TREE_DEPTH;
		}
		if (reason.startsWith("menu count limit")
			|| reason.startsWith("menu total entry count limit")
			|| reason.startsWith("invalid menu entry count"))
		{
			return SubmenuOutcome.FALLBACK_TREE_COUNT;
		}
		if (reason.startsWith("menu identity cycle"))
		{
			return SubmenuOutcome.FALLBACK_TREE_CYCLE;
		}
		if (reason.contains("bounds"))
		{
			return SubmenuOutcome.FALLBACK_INVALID_BOUNDS;
		}
		return SubmenuOutcome.FALLBACK_OTHER;
	}

	static String unsafeScrolledChild(SubmenuTree tree, int[] chain,
		ScrollableMenuViewport rootViewport, int canvasHeight)
	{
		return validateScrolledChild(tree, chain, rootViewport, canvasHeight, null).reason;
	}

	/**
	 * @param proof the probe result the chain came from. A detached child counts as natively
	 * drawn only when that result's exact reading resolved it: every marked pixel of its visible
	 * rectangle changed.
	 */
	static ScrollChildValidation validateScrolledChild(SubmenuTree tree, int[] chain,
		ScrollableMenuViewport rootViewport, int canvasHeight,
		NativeSubmenuProbe.Result proof)
	{
		boolean detached = false;
		for (int candidate : chain)
		{
			SubmenuTree.Node child = tree.nodes.get(candidate + 1);
			SubmenuTree.Node parent = tree.nodes.get(child.parentNode);
			if (child.entries.length == 0 || child.bounds.height > canvasHeight
				|| (long) child.entries.length * MenuRenderer.ROW_HEIGHT
					+ MenuRenderer.HEADER_HEIGHT > canvasHeight)
			{
				return ScrollChildValidation.reject("active child may need an unavailable submenu scroll offset");
			}
			int row = parent.entries.length - 1 - child.parentEntryIndex;
			int rowTop;
			if (child.parentNode == 0)
			{
				if (!rootViewport.containsRow(row))
				{
					return ScrollChildValidation.reject("active child parent row is outside the root viewport"
						+ " row=" + row + " scroll=" + rootViewport.scroll);
				}
				rowTop = rootViewport.rowTop(row);
			}
			else
			{
				rowTop = parent.bounds.y + MenuRenderer.HEADER_HEIGHT
					+ row * MenuRenderer.ROW_HEIGHT;
			}
			if (rowTop >= canvasHeight || rowTop + MenuRenderer.ROW_HEIGHT <= 0)
			{
				return ScrollChildValidation.reject("active child parent row is outside the canvas"
					+ " row=" + row + " rowTop=" + rowTop);
			}
			if (child.bounds.y >= rowTop + MenuRenderer.ROW_HEIGHT
				|| child.bounds.y + child.bounds.height <= rowTop)
			{
				// Native drawing can retain a child's old position for a scroll
				// transition. Theme it at that exact position only when the same
				// probe proves this child was actually drawn there.
				if (!exactlyDrawn(proof, candidate))
				{
					return ScrollChildValidation.reject("active child bounds are detached from its visible parent row"
						+ " row=" + row + " rowTop=" + rowTop + " child=" + child.bounds
						+ " exclusive=" + (proof == null || candidate >= proof.changed.length
							? 0 : proof.changed[candidate])
						+ " footprintMatches=" + (proof == null ? 0 : proof.footprintMatches)
						+ " exact=" + (proof == null ? "none" : proof.exact.state));
				}
				detached = true;
			}
		}
		return detached ? ScrollChildValidation.DETACHED : ScrollChildValidation.ATTACHED;
	}

	/** The probe's own exact reading resolved a chain containing this candidate. */
	private static boolean exactlyDrawn(NativeSubmenuProbe.Result proof, int candidate)
	{
		if (proof == null || !proof.restored || !proof.framebufferVerified
			|| proof.exact.state != ExactCoverageResolver.State.RESOLVED)
		{
			return false;
		}
		for (int proved : proof.exact.chain)
		{
			if (proved == candidate)
			{
				return true;
			}
		}
		return false;
	}

	static final class ScrollChildValidation
	{
		static final ScrollChildValidation ATTACHED = new ScrollChildValidation(null, false);
		static final ScrollChildValidation DETACHED = new ScrollChildValidation(null, true);
		final String reason;
		final boolean detached;

		private ScrollChildValidation(String reason, boolean detached)
		{
			this.reason = reason;
			this.detached = detached;
		}

		static ScrollChildValidation reject(String reason)
		{
			return new ScrollChildValidation(reason, false);
		}
	}

	private static SubmenuOutcome failureOutcome(NativeSubmenuProbe.Result result)
	{
		if (result.state == NativeSubmenuProbe.State.AMBIGUOUS)
		{
			return SubmenuOutcome.FALLBACK_AMBIGUOUS;
		}
		if (result.reason.startsWith("candidate pixel area limit"))
		{
			return SubmenuOutcome.FALLBACK_COMPLEXITY;
		}
		if ("framebuffer restoration failed".equals(result.reason))
		{
			return SubmenuOutcome.FALLBACK_RESTORE_FAILURE;
		}
		if ("native draw missed buffer".equals(result.reason))
		{
			return SubmenuOutcome.FALLBACK_NATIVE_DRAW_MISSED;
		}
		if (result.reason.contains("raster/provider")
			|| result.reason.contains("buffer identity")
			|| "invalid framebuffer".equals(result.reason))
		{
			return SubmenuOutcome.FALLBACK_BUFFER_MISMATCH;
		}
		if (result.state == NativeSubmenuProbe.State.GEOMETRY_CHANGED)
		{
			return SubmenuOutcome.FALLBACK_GEOMETRY_CHANGED;
		}
		if (result.reason.contains("geometry"))
		{
			return SubmenuOutcome.FALLBACK_INVALID_BOUNDS;
		}
		return SubmenuOutcome.FALLBACK_OTHER;
	}

	synchronized void clearPrepared()
	{
		prepared = false;
		preparedRoot = null;
		preparedState = null;
		preparedScrollable = false;
		preparedScroll = 0;
		preparedFirstRow = 0;
		preparedTree = null;
		preparedMenus = null;
		preparedChains = null;
		preparedViewport = null;
		preparedMouse = null;
	}

	private RowBuffer submenuRowBuffer(int depth)
	{
		while (submenuRows.size() <= depth)
		{
			submenuRows.add(new RowBuffer());
		}
		return submenuRows.get(depth);
	}

	private static final class RowBuffer
	{
		private final List<MenuRenderer.Row> rows = new ArrayList<>();
		private final List<MenuRenderer.Row> pool = new ArrayList<>();
		private MenuRenderer.TargetKind headerKind;
	}

	static boolean preparedFrameMatches(boolean menuOpen, Menu currentRoot, Menu preparedRoot)
	{
		return menuOpen && currentRoot == preparedRoot;
	}

	/**
	 * Whether the regular renderer should replace the vanilla menu. Scrollable roots and submenu
	 * trees are prepared by {@link #prepareSubmenuFrame} before this path is reached.
	 */
	boolean shouldDraw()
	{
		return drawableEntries() != null;
	}

	/**
	 * @return the open menu's entries, or null if we shouldn't replace it. The client copies the
	 * array on every call, so callers fetch it once.
	 */
	private MenuEntry[] drawableEntries()
	{
		if (!canInspectMenu(failed, client.isMenuOpen(), client.isMenuScrollable()))
		{
			return null;
		}
		Menu menu = client.getMenu();
		if (menu == null)
		{
			return null;
		}
		MenuEntry[] entries = menu.getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return null;
		}
		return containsSubMenu(entries) ? null : entries;
	}

	static boolean canInspectMenu(boolean failed, boolean open, boolean scrollable)
	{
		return !failed && open && !scrollable;
	}

	static boolean containsSubMenu(MenuEntry[] entries)
	{
		for (MenuEntry entry : entries)
		{
			if (entry != null && entry.getSubMenu() != null)
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public synchronized Dimension render(Graphics2D graphics)
	{
		if (externallyConsumed)
		{
			externallyConsumed = false;
			clearPrepared();
			return null;
		}
		if (prepared)
		{
			try
			{
				boolean open = client.isMenuOpen();
				Menu currentRoot = open ? client.getMenu() : null;
				// The event is already consumed, so a drop here leaves this frame without a
				// menu. Only structural changes may cause it; the pointer is never read here.
				boolean rootChanged = open && (!preparedFrameMatches(true, currentRoot, preparedRoot)
					|| client.isMenuScrollable() != preparedScrollable
					|| (preparedScrollable && client.getMenuScroll() != preparedScroll)
					|| preparedState == null);
				String change = !open ? null : rootChanged ? "root or scroll"
					: preparedState.mismatchReason(currentRoot);
				// A change that leaves every rendering input, link and rectangle as prepared does
				// not change the image, so that image is still the live menu's.
				boolean equivalent = change != null && !rootChanged && imageMatchesLiveMenus(currentRoot);
				if (!open)
				{
					submenuClosedDrops++;
					traceFinalDrop("menu closed after event consumed");
				}
				else if (change != null && !equivalent)
				{
					submenuChangedDrops++;
					submenuBlitFailed = true;
					traceFinalDrop(change + " changed after event consumed");
				}
				else if (graphics.drawImage(submenuImage, preparedX, preparedY, null))
				{
					submenuBlits++;
					openingBlitted = true;
					if (equivalent)
					{
						submenuEquivalentBlits++;
						traceOutcome("FINAL_EQUIVALENT", change + " changed after event consumed; image unchanged");
					}
				}
				else
				{
					submenuBlitFailed = true;
					traceFinalDrop("drawImage returned false after event consumed");
				}
			}
			catch (RuntimeException e)
			{
				submenuBlitFailed = true;
				traceFinalDrop(e.getClass().getSimpleName() + ": " + e.getMessage());
				log.warn("Submenu final image draw failed; native submenus until the menu is next opened", e);
			}
			finally
			{
				clearPrepared();
			}
			return null;
		}
		MenuEntry[] entries = drawableEntries();
		if (entries == null)
		{
			return null;
		}
		try
		{
			renderMenu(graphics, client.getMenu(), entries);
		}
		catch (RuntimeException e)
		{
			failed = true;
			log.warn("Right Click Styler failed to draw the menu; using the vanilla menu for the rest of this session", e);
		}
		return null;
	}

	private void renderMenu(Graphics2D graphics, Menu menu, MenuEntry[] entries)
	{
		setNativeBounds(bounds, menu);
		Theme theme = themeManager.effectiveTheme();

		fillRows(entries);

		int hoverIndex = hoverIndex(client.getMouseCanvasPosition());
		Player local = client.getLocalPlayer();
		int localLevel = local != null ? local.getCombatLevel() : 0;
		renderer.render(graphics, client, theme, config, rows, bounds, hoverIndex, localLevel);
	}

	static void setNativeBounds(Rectangle target, Menu menu)
	{
		target.setBounds(menu.getMenuX(), menu.getMenuY(), menu.getMenuWidth(), menu.getMenuHeight());
	}

	/**
	 * Entries are stored bottom-up; rows are drawn top-down. A null entry still occupies a row so
	 * the hover maths stays aligned with the vanilla click areas.
	 *
	 */
	private void fillRows(MenuEntry[] entries)
	{
		fillRows(entries, null, null, rows, rowPool);
	}

	private static void fillRows(MenuEntry[] entries, boolean[] childFlags,
		boolean[] childOpensLeft,
		List<MenuRenderer.Row> rows, List<MenuRenderer.Row> rowPool)
	{
		fillRows(entries, childFlags, childOpensLeft, rows, rowPool, null, null);
	}

	/** The already-filled row whose entry opened {@code node}, or null when it cannot be matched. */
	static MenuRenderer.Row openingRow(SubmenuTree tree, SubmenuTree.Node node, SubmenuTree.Node parent,
		List<MenuRenderer.Row> parentRows)
	{
		if (node.parentNode < 0 || node.parentNode >= tree.nodes.size()
			|| tree.nodes.get(node.parentNode) != parent)
		{
			return null;
		}
		int slot = parent.entries.length - 1 - node.parentEntryIndex;
		return slot >= 0 && slot < parentRows.size() ? parentRows.get(slot) : null;
	}

	/**
	 * Fills one drawn menu. A child menu takes target context from the row that opened it: its
	 * header, and any child entry repeating that row's target, colour like that row does.
	 *
	 * @param parentRow the opening row, null for the root
	 * @return the header's target kind, or null when the header is not the opening row's target
	 */
	static MenuRenderer.TargetKind fillMenuRows(SubmenuTree.Node node, MenuRenderer.Row parentRow,
		List<MenuRenderer.Row> rows, List<MenuRenderer.Row> rowPool)
	{
		MenuRenderer.TargetKind parentKind = parentRow == null ? null : parentRow.kind();
		String parentPlainTarget = null;
		if (parentKind != null && parentKind != MenuRenderer.TargetKind.OTHER && parentRow.target() != null)
		{
			parentPlainTarget = Text.removeTags(parentRow.target()).trim();
		}
		boolean inherits = parentPlainTarget != null && !parentPlainTarget.isEmpty();
		fillRows(node.entries, node.childFlags, node.childOpensLeft, rows, rowPool,
			inherits ? parentKind : null, inherits ? parentPlainTarget : null);
		return parentRow != null && node.headerFromTarget ? parentKind : null;
	}

	/**
	 * A child entry with no actor, object or item of its own that repeats the opening row's
	 * target (optionally followed by appended text, such as an ID suffix) names that same thing,
	 * so it takes that row's kind. Every other child keeps its own classification.
	 */
	static MenuRenderer.TargetKind childKind(MenuEntry entry, MenuRenderer.TargetKind parentKind,
		String parentPlainTarget)
	{
		MenuRenderer.TargetKind own = classify(entry);
		if (own != MenuRenderer.TargetKind.OTHER || parentKind == null || parentPlainTarget == null
			|| parentPlainTarget.isEmpty())
		{
			return own;
		}
		String target = entry.getTarget();
		if (target == null || !target.contains(parentPlainTarget))
		{
			return own;
		}
		String plain = Text.removeTags(target).trim();
		return plain.equals(parentPlainTarget) || plain.startsWith(parentPlainTarget + " ")
			? parentKind : own;
	}

	private static void fillRows(MenuEntry[] entries, boolean[] childFlags,
		boolean[] childOpensLeft,
		List<MenuRenderer.Row> rows, List<MenuRenderer.Row> rowPool,
		MenuRenderer.TargetKind parentKind, String parentPlainTarget)
	{
		rows.clear();
		for (int i = entries.length - 1; i >= 0; i--)
		{
			int slot = rows.size();
			if (slot == rowPool.size())
			{
				rowPool.add(new MenuRenderer.Row(null, null, MenuRenderer.TargetKind.OTHER));
			}
			MenuRenderer.Row row = rowPool.get(slot);
			MenuEntry entry = entries[i];
			if (entry != null)
			{
				row.set(entry.getOption(), entry.getTarget(),
					parentKind == null ? classifyWithItemOwner(entry, entries)
						: childKind(entry, parentKind, parentPlainTarget),
					childFlags != null && childFlags[i],
					childOpensLeft != null && childOpensLeft[i]);
			}
			else
			{
				row.set(null, null, MenuRenderer.TargetKind.OTHER);
			}
			rows.add(row);
		}
	}

	private int hoverIndex(Point mouse)
	{
		return hoverIndex(bounds, rows.size(), mouse);
	}

	static int hoverIndex(Rectangle bounds, int rowCount, Point mouse)
	{
		if (mouse == null || mouse.getX() < bounds.x || mouse.getX() >= bounds.x + bounds.width)
		{
			return -1;
		}
		int relativeY = mouse.getY() - (bounds.y + MenuRenderer.HEADER_HEIGHT);
		if (relativeY < 0)
		{
			return -1;
		}
		int index = relativeY / MenuRenderer.ROW_HEIGHT;
		return index < rowCount ? index : -1;
	}

	static MenuRenderer.TargetKind classify(MenuEntry entry)
	{
		if (entry.getNpc() != null)
		{
			return MenuRenderer.TargetKind.NPC;
		}
		if (entry.getPlayer() != null)
		{
			return MenuRenderer.TargetKind.PLAYER;
		}
		MenuAction type = entry.getType();
		if (isGameObjectAction(type))
		{
			return MenuRenderer.TargetKind.OBJECT;
		}
		if (isGroundItemAction(type) || entry.getItemId() != NO_ITEM)
		{
			return MenuRenderer.TargetKind.ITEM;
		}
		return MenuRenderer.TargetKind.OTHER;
	}

	/** RuneLite inventory plugins copy the Examine target into a new submenu parent. */
	private static MenuRenderer.TargetKind classifyWithItemOwner(MenuEntry entry, MenuEntry[] entries)
	{
		MenuRenderer.TargetKind own = classify(entry);
		if (own != MenuRenderer.TargetKind.OTHER || entry.getType() != MenuAction.RUNELITE
			|| entry.getSubMenu() == null || entry.getTarget() == null)
		{
			return own;
		}
		String target = Text.removeTags(entry.getTarget()).trim();
		if (target.isEmpty())
		{
			return own;
		}
		boolean itemOwner = false;
		for (MenuEntry candidate : entries)
		{
			if (candidate == null || candidate == entry || candidate.getTarget() == null
				|| !target.equals(Text.removeTags(candidate.getTarget()).trim()))
			{
				continue;
			}
			MenuRenderer.TargetKind candidateKind = classify(candidate);
			if (candidateKind == MenuRenderer.TargetKind.ITEM && candidate.getItemId() != NO_ITEM)
			{
				itemOwner = true;
			}
			else if (candidateKind != MenuRenderer.TargetKind.OTHER)
			{
				return own;
			}
		}
		return itemOwner ? MenuRenderer.TargetKind.ITEM : own;
	}

	static boolean isGameObjectAction(MenuAction type)
	{
		if (type == null)
		{
			return false;
		}
		switch (type)
		{
			case ITEM_USE_ON_GAME_OBJECT:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case EXAMINE_OBJECT:
				return true;
			default:
				return false;
		}
	}

	private static boolean isGroundItemAction(MenuAction type)
	{
		if (type == null)
		{
			return false;
		}
		switch (type)
		{
			case ITEM_USE_ON_GROUND_ITEM:
			case WIDGET_TARGET_ON_GROUND_ITEM:
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case EXAMINE_ITEM_GROUND:
				return true;
			default:
				return false;
		}
	}
}
