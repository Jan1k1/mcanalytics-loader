package net.sniffstudio.mcanalytics.loader.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoaderCommandsTest {

    @Test
    @DisplayName("Every loader subcommand is offered with no argument typed")
    void offersEverySubcommand() {
        assertThat(LoaderCommands.suggest(new String[0])).containsExactly("pair", "status", "update");
        assertThat(LoaderCommands.suggest(null)).containsExactly("pair", "status", "update");
    }

    @Test
    @DisplayName("A typed prefix narrows the suggestions and is case insensitive")
    void narrowsOnPrefix() {
        assertThat(LoaderCommands.suggest(new String[]{"s"})).containsExactly("status");
        assertThat(LoaderCommands.suggest(new String[]{"UP"})).containsExactly("update");
        assertThat(LoaderCommands.suggest(new String[]{"zz"})).isEmpty();
    }

    @Test
    @DisplayName("No suggestion is offered for the pairing code itself")
    void neverSuggestsPairingCodes() {
        assertThat(LoaderCommands.suggest(new String[]{"pair", ""})).isEmpty();
    }
}
