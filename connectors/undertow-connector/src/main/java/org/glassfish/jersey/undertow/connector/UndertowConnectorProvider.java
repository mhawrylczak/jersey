package org.glassfish.jersey.undertow.connector;

import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.client.spi.ConnectorProvider;
import org.xnio.*;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.Configuration;
import java.io.IOException;
import java.nio.ByteBuffer;


public class UndertowConnectorProvider implements ConnectorProvider {

    @Override
    public Connector getConnector(Client client, Configuration runtimeConfig) {
        return new UndertowConnector(client, runtimeConfig);
    }
}
