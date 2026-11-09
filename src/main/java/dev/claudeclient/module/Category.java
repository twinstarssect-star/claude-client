package dev.claudeclient.module;

public enum Category {
	SEED("Seed"),
	FINDERS("Finders"),
	RENDER("Render"),
	PLAYER("Player"),
	CLIENT("Client");

	public final String label;

	Category(String label) {
		this.label = label;
	}
}
