package net.sniffstudio.mcanalytics.loader.config;

import java.time.Instant;

public record LoaderCredentials(
        String connectorToken,
        String networkId,
        String serverId,
        String serverName,
        String platform,
        Instant pairedAt
) {
    public boolean isComplete() {
        return connectorToken != null && !connectorToken.isBlank()
                && networkId != null && !networkId.isBlank()
                && serverId != null && !serverId.isBlank();
    }
}
