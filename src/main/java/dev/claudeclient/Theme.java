package dev.claudeclient;

/** Warm dark palette with a clay accent, used by the menu, HUD and chat prefix. All colours are ARGB. */
public final class Theme {
	public static final int PANEL = 0xFF262624;
	public static final int SIDEBAR = 0xFF1F1E1D;
	public static final int ROW_HOVER = 0xFF302F2C;
	public static final int ROW_SELECTED = 0xFF383632;
	public static final int BORDER = 0xFF3D3B37;
	public static final int ACCENT = 0xFFD97757;
	public static final int ACCENT_DIM = 0xFF7A4433;
	public static final int TEXT = 0xFFF5F4EE;
	public static final int SUB = 0xFFA8A49B;
	public static final int OFF = 0xFF55534E;
	public static final int GOOD = 0xFF9BCB86;
	public static final int HUD_BG = 0x90161514;

	/** RGB without alpha, for chat styles. */
	public static int rgb(int argb) {
		return argb & 0xFFFFFF;
	}

	private Theme() {}
}
