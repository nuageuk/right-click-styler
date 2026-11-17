package com.rightclickstyler;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.ColorJButton;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RightClickStylerPanelTest
{
	@Test
	public void disablesHtmlBeforeFirstThemeNameRender()
	{
		DefaultListCellRenderer renderer = RightClickStylerPanel.themeListCellRenderer();
		Theme theme = BuiltInThemes.find(BuiltInThemes.DEFAULT_ID)
			.withIdentity("literal", "<html>Rune Ω !?", false);

		Component component = renderer.getListCellRendererComponent(
			new JList<>(), theme, 0, false, false);

		assertEquals("<html>Rune Ω !?", ((JLabel) component).getText());
		assertNull(((JLabel) component).getClientProperty(BasicHTML.propertyKey));
	}

	@Test
	public void targetColourResetOnlyClearsTheFiveColourKeys()
	{
		List<String> cleared = new ArrayList<>();
		RightClickStylerPanel.resetTargetColourSettings(cleared::add);
		assertEquals(Arrays.asList(
			RightClickStylerConfig.KEY_NPC_COLOUR,
			RightClickStylerConfig.KEY_PLAYER_COLOUR,
			RightClickStylerConfig.KEY_ITEM_COLOUR,
			RightClickStylerConfig.KEY_OBJECT_COLOUR,
			RightClickStylerConfig.KEY_OTHER_COLOUR), cleared);
	}

	@Test
	public void targetControlsFollowModeAcrossReloadThemeSwitchAndReopen() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			MutableTargetConfig config = new MutableTargetConfig();
			ThemeManager themes = new ThemeManager(() -> "[]", (key, value) -> {}, config, new Gson());
			RightClickStylerPanel panel = new RightClickStylerPanel(
				new LocalPlayerSnapshot(), themes, config, null, null);
			assertTargetControls(panel, false, config);
			int gameModeHeight = targetModeRow(panel).getPreferredSize().height;

			config.mode = TargetMode.CUSTOM;
			panel.reloadFromConfig();
			assertTargetControls(panel, true, config);
			assertTrue("hidden explanation should not reserve space",
				gameModeHeight > targetModeRow(panel).getPreferredSize().height);
			JPanel themeRow = (JPanel) panel.getComponent(1);
			@SuppressWarnings("unchecked")
			JComboBox<Theme> themeCombo = (JComboBox<Theme>) themeRow.getComponent(0);
			themeCombo.setSelectedItem(BuiltInThemes.find("glass"));
			assertTargetControls(panel, true, config);

			config.mode = TargetMode.GAME;
			panel.reloadFromConfig();
			assertTargetControls(panel, false, config);
			RightClickStylerPanel reopened = new RightClickStylerPanel(
				new LocalPlayerSnapshot(), themes, config, null, null);
			assertTargetControls(reopened, false, config);
		});
	}

	private static void assertTargetControls(RightClickStylerPanel panel, boolean enabled,
		MutableTargetConfig config)
	{
		// Targets is the seventh section added by the panel constructor.
		CollapsibleSection targets = (CollapsibleSection) panel.getComponent(6);
		JPanel body = (JPanel) targets.getComponent(1);
		JPanel modeRow = targetModeRow(panel);
		JComboBox<?> mode = (JComboBox<?>) modeRow.getComponent(1);
		assertEquals(config.mode, mode.getSelectedItem());
		JLabel explanation = (JLabel) ((BorderLayout) modeRow.getLayout())
			.getLayoutComponent(BorderLayout.SOUTH);
		assertEquals("Game colours active. Custom to edit.", explanation.getText());
		assertTrue("explanation fits even with a sidebar scrollbar",
			explanation.getPreferredSize().width <= PluginPanel.PANEL_WIDTH
				- 2 * PluginPanel.BORDER_OFFSET - PluginPanel.SCROLLBAR_WIDTH);
		assertEquals(!enabled, explanation.isVisible());
		for (int i = 0; i < 5; i++)
		{
			JPanel row = (JPanel) body.getComponent(i + 1);
			JLabel label = (JLabel) row.getComponent(0);
			ColorJButton button = (ColorJButton) row.getComponent(1);
			assertEquals(label.getText(), enabled, label.isEnabled());
			assertEquals(label.getText(), enabled, button.isEnabled());
			assertEquals(label.getText(), "#" + Integer.toHexString(config.colours[i].getRGB() & 0xFFFFFF)
				.toUpperCase(), button.getText());
			if (enabled)
			{
				assertEquals(label.getText(), config.colours[i], button.getColor());
			}
			else
			{
				assertNotEquals(label.getText(), config.colours[i], button.getColor());
				assertTrue(label.getText(), saturation(button.getColor()) < saturation(config.colours[i]));
			}
		}
		JButton reset = (JButton) body.getComponent(6);
		assertEquals(enabled, reset.isEnabled());
	}

	private static JPanel targetModeRow(RightClickStylerPanel panel)
	{
		CollapsibleSection targets = (CollapsibleSection) panel.getComponent(6);
		JPanel body = (JPanel) targets.getComponent(1);
		return (JPanel) body.getComponent(0);
	}

	private static float saturation(Color colour)
	{
		return Color.RGBtoHSB(colour.getRed(), colour.getGreen(), colour.getBlue(), null)[1];
	}

	private static final class MutableTargetConfig implements RightClickStylerConfig
	{
		private TargetMode mode = TargetMode.GAME;
		private final Color[] colours = {
			new Color(0x110022), new Color(0x220033), new Color(0x3300FF),
			new Color(0x440055), new Color(0x550066)
		};

		@Override
		public TargetMode targetMode()
		{
			return mode;
		}

		@Override
		public Color npcColour()
		{
			return colours[0];
		}

		@Override
		public Color playerColour()
		{
			return colours[1];
		}

		@Override
		public Color itemColour()
		{
			return colours[2];
		}

		@Override
		public Color objectColour()
		{
			return colours[3];
		}

		@Override
		public Color otherColour()
		{
			return colours[4];
		}
	}
}
