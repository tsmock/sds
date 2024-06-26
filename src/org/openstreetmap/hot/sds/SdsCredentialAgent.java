// License: GPL. For details, see LICENSE file.
package org.openstreetmap.hot.sds;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Component;
import java.net.Authenticator.RequestorType;
import java.net.PasswordAuthentication;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.text.html.HTMLEditorKit;

import org.openstreetmap.josm.data.oauth.IOAuthToken;
import org.openstreetmap.josm.gui.io.CredentialDialog;
import org.openstreetmap.josm.gui.widgets.HtmlPanel;
import org.openstreetmap.josm.io.DefaultProxySelector;
import org.openstreetmap.josm.io.auth.AbstractCredentialsAgent;
import org.openstreetmap.josm.io.auth.CredentialsAgent;
import org.openstreetmap.josm.io.auth.CredentialsAgentException;
import org.openstreetmap.josm.io.auth.CredentialsAgentResponse;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Factored after JOSM's JosmPreferencesCredentialAgent.
 */
public class SdsCredentialAgent extends AbstractCredentialsAgent {

    private final Map<RequestorType, PasswordAuthentication> sdsMemoryCredentialsCache = new EnumMap<>(RequestorType.class);

    /**
     * @see CredentialsAgent#lookup(RequestorType, String)
     */
    @Override
    public PasswordAuthentication lookup(RequestorType requestorType, String host) {
        if (requestorType == null)
            return null;
        String user;
        String password;
        switch(requestorType) {
        case SERVER:
            user = Config.getPref().get("sds-server.username", null);
            password = Config.getPref().get("sds-server.password", null);
            if (user == null)
                return null;
            return new PasswordAuthentication(user, password == null ? new char[0] : password.toCharArray());
        case PROXY:
            user = Config.getPref().get(DefaultProxySelector.PROXY_USER, null);
            password = Config.getPref().get(DefaultProxySelector.PROXY_PASS, null);
            if (user == null)
                return null;
            return new PasswordAuthentication(user, password == null ? new char[0] : password.toCharArray());
        }
        return null;
    }

    /**
     * @see CredentialsAgent#store(RequestorType, String, PasswordAuthentication)
     */
    @Override
    public void store(RequestorType requestorType, String host, PasswordAuthentication credentials) throws CredentialsAgentException {
        if (requestorType == null)
            return;
        switch(requestorType) {
        case SERVER:
            Config.getPref().put("sds-server.username", credentials.getUserName());
            if (credentials.getPassword() == null) {
                Config.getPref().put("sds-server.password", null);
            } else {
                Config.getPref().put("sds-server.password", String.valueOf(credentials.getPassword()));
            }
            break;
        case PROXY:
            Config.getPref().put(DefaultProxySelector.PROXY_USER, credentials.getUserName());
            if (credentials.getPassword() == null) {
                Config.getPref().put(DefaultProxySelector.PROXY_PASS, null);
            } else {
                Config.getPref().put(DefaultProxySelector.PROXY_PASS, String.valueOf(credentials.getPassword()));
            }
            break;
        }
    }

    @Override
    public IOAuthToken lookupOAuthAccessToken(String host) {
        throw new UnsupportedOperationException("SDS does not support OAuth tokens");
    }

    @Override
    public Component getPreferencesDecorationPanel() {
        HtmlPanel pnlMessage = new HtmlPanel();
        HTMLEditorKit kit = (HTMLEditorKit) pnlMessage.getEditorPane().getEditorKit();
        kit.getStyleSheet().addRule(
                ".warning-body {background-color:rgb(253,255,221);padding: 10pt; " +
                "border-color:rgb(128,128,128);border-style: solid;border-width: 1px;}");
        pnlMessage.setText(
                tr(
                        "<html><body>"
                        + "<p class=\"warning-body\">"
                        + "<strong>Warning:</strong> The password is stored in plain text in the JOSM preferences file. "
                        + "</p>"
                        + "</body></html>"
                )
        );
        return pnlMessage;
    }

    @Override
    public String getSaveUsernameAndPasswordCheckboxText() {
        return tr("Save user and password (unencrypted)");
    }

    @Override
    public void storeOAuthAccessToken(String host, IOAuthToken accessToken) {
        throw new UnsupportedOperationException("SDS does not support OAuth tokens");
    }

    @Override
    public CredentialsAgentResponse getCredentials(RequestorType requestorType, String host, boolean noSuccessWithLastResponse)
            throws CredentialsAgentException {
        if (requestorType == null)
            return null;
        PasswordAuthentication credentials = lookup(requestorType, host);
        String username = (credentials == null || credentials.getUserName() == null) ? "" : credentials.getUserName();
        String password = (credentials == null || credentials.getPassword() == null) ? "" : String.valueOf(credentials.getPassword());

        CredentialsAgentResponse response = new CredentialsAgentResponse();

        /*
         * Last request was successful and there was no credentials stored
         * in file (or only the username is stored).
         * -> Try to recall credentials that have been entered
         * manually in this session.
         */
        if (!noSuccessWithLastResponse && sdsMemoryCredentialsCache.containsKey(requestorType) &&
                (credentials == null || credentials.getPassword() == null || credentials.getPassword().length == 0)) {
            PasswordAuthentication pa = sdsMemoryCredentialsCache.get(requestorType);
            response.setUsername(pa.getUserName());
            response.setPassword(pa.getPassword());
            response.setCanceled(false);
        /*
         * Prompt the user for credentials. This happens the first time each
         * josm start if the user does not save the credentials to preference
         * file (username=="") and each time after authentication failed
         * (noSuccessWithLastResponse == true).
         */
        } else if (noSuccessWithLastResponse || "".equals(username) || "".equals(password)) {
            CredentialDialog dialog;
            switch(requestorType) {
            case SERVER:
                dialog = SdsCredentialDialog.getSdsApiCredentialDialog(username, password, host, getSaveUsernameAndPasswordCheckboxText());
                break;
            case PROXY:
                dialog = CredentialDialog.getHttpProxyCredentialDialog(username, password, host, getSaveUsernameAndPasswordCheckboxText());
                break;
            default:
                return null;
            }
            dialog.setVisible(true);
            response.setCanceled(dialog.isCanceled());
            if (dialog.isCanceled())
                return response;
            response.setUsername(dialog.getUsername());
            response.setPassword(dialog.getPassword());
            if (dialog.isSaveCredentials()) {
                store(requestorType, host, new PasswordAuthentication(
                        response.getUsername(),
                        response.getPassword()
                ));
            /*
             * User decides not to save credentials to file. Keep it
             * in memory so we don't have to ask over and over again.
             */
            } else {
                PasswordAuthentication pa = new PasswordAuthentication(dialog.getUsername(), dialog.getPassword());
                sdsMemoryCredentialsCache.put(requestorType, pa);
            }
        /*
         * We got it from file.
         */
        } else {
            response.setUsername(username);
            response.setPassword(password.toCharArray());
            response.setCanceled(false);
        }
        return response;
    }

}
