package com.rightclickstyler;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Launches the released plugin alongside a local-only diagnostic subscriber. */
public final class SubmenuDiagnosticLauncher
{
	private SubmenuDiagnosticLauncher()
	{
	}

	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RightClickStylerPlugin.class, SubmenuDiagnosticPlugin.class);
		RuneLite.main(args);
	}
}
