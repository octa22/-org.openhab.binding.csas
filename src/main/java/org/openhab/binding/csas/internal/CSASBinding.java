/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 * <p>
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.csas.internal;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.openhab.binding.csas.CSASBindingProvider;

import org.apache.commons.lang.StringUtils;
import org.openhab.core.binding.AbstractActiveBinding;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implement this class if you are going create an actively polling service
 * like querying a Website/Device.
 *
 * @author Ondrej Pecta
 * @since 1.9.0
 */
public class CSASBinding extends AbstractActiveBinding<CSASBindingProvider> {

    private static final Logger logger =
            LoggerFactory.getLogger(CSASBinding.class);

    /**
     * The BundleContext. This is only valid when the bundle is ACTIVE. It is set in the activate()
     * method and must not be accessed anymore once the deactivate() method was called or before activate()
     * was called.mvn archetype:generate -B -DarchetypeGroupId=org.openhab.archetype \
     -DarchetypeArtifactId=org.openhab.archetype.binding \
     -DarchetypeVersion=1.8.0-SNAPSHOT -Dauthor="Ondrej Pecta" -Dversion=1.9.0 \
     -DartifactId=org.openhab.binding.csas \
     -Dpackage=org.openhab.binding.csas \
     -Dbinding-name=CSAS
     */
    private BundleContext bundleContext;

    //Constants
    final private String NETBANKING_V3 = "https://www.csas.cz/webapi/api/v3/netbanking/";

    /**
     * the refresh interval which is used to poll values from the CSAS
     * server (optional, defaults to 60000ms)
     */
    private long refreshInterval = 1800000;
    private String clientId = "";
    private String clientSecret = "";
    private String refreshToken = "";
    private String accessToken = "";
    private String webAPIKey = "";

    //Gson parser
    private JsonParser parser = new JsonParser();

    //Account list
    HashMap<String, String> accountList = new HashMap<>();

    public CSASBinding() {
    }


    /**
     * Called by the SCR to activate the component with its configuration read from CAS
     *
     * @param bundleContext BundleContext of the Bundle that defines this component
     * @param configuration Configuration properties for this component obtained from the ConfigAdmin service
     */
    public void activate(final BundleContext bundleContext, final Map<String, Object> configuration) {
        this.bundleContext = bundleContext;

        // the configuration is guaranteed not to be null, because the component definition has the
        // configuration-policy set to require. If set to 'optional' then the configuration may be null

        readConfiguration(configuration);
        // read further config parameters here ...

        refreshToken();
        getAccounts();
        getCards();
        getBuildingSavings();
        getPensions();
        listUnboundAccounts();
        setProperlyConfigured(!accessToken.equals(""));
    }

    private void refreshToken() {
        String url = null;

        try {
            String urlParameters = "client_id=" + clientId + "&client_secret=" + clientSecret + "&redirect_uri=https://localhost/code&grant_type=refresh_token&refresh_token=" + refreshToken;
            url = "https://www.csas.cz/widp/oauth2/token";

            byte[] postData = urlParameters.getBytes(StandardCharsets.UTF_8);

            URL cookieUrl = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) cookieUrl.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Length", Integer.toString(postData.length));
            try (DataOutputStream wr = new DataOutputStream(connection.getOutputStream())) {
                wr.write(postData);
            }

            InputStream response = connection.getInputStream();
            String line = readResponse(response);
            logger.debug("CSAS response: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            accessToken = jobject.get("access_token").getAsString();

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS token: " + e.toString());
        }
    }

    private String readResponse(InputStream response) throws Exception {
        String line;
        StringBuilder body = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(response));

        while ((line = reader.readLine()) != null) {
            body.append(line).append("\n");
        }
        line = body.toString();
        logger.debug(line);
        return line;
    }

    private void readConfiguration(final Map<String, Object> configuration) {
        // to override the default refresh interval one has to add a
        // parameter to openhab.cfg like <bindingName>:refresh=<intervalInMs>
        String refreshIntervalString = (String) configuration.get("refresh");
        if (StringUtils.isNotBlank(refreshIntervalString)) {
            refreshInterval = Long.parseLong(refreshIntervalString);
        }

        String clientIdString = (String) configuration.get("clientId");
        if (StringUtils.isNotBlank(clientIdString)) {
            clientId = clientIdString;
        }

        String clientSecretString = (String) configuration.get("clientSecret");
        if (StringUtils.isNotBlank(clientSecretString)) {
            clientSecret = clientSecretString;
        }

        String refreshTokenString = (String) configuration.get("refreshToken");
        if (StringUtils.isNotBlank(refreshTokenString)) {
            refreshToken = refreshTokenString;
        }

        String webAPIKeyString = (String) configuration.get("webAPIKey");
        if (StringUtils.isNotBlank(webAPIKeyString)) {
            webAPIKey = webAPIKeyString;
        }

    }

    /**
     * Called by the SCR when the configuration of a binding has been changed through the ConfigAdmin service.
     * @param configuration Updated configuration properties
     */
    public void modified(final Map<String, Object> configuration) {
        // update the internal configuration accordingly
        readConfiguration(configuration);
        refreshToken();
    }

    /**
     * Called by the SCR to deactivate the component when either the configuration is removed or
     * mandatory references are no longer satisfied or the component has simply been stopped.
     * @param reason Reason code for the deactivation:<br>
     * <ul>
     * <li> 0 – Unspecified
     * <li> 1 – The component was disabled
     * <li> 2 – A reference became unsatisfied
     * <li> 3 – A configuration was changed
     * <li> 4 – A configuration was deleted
     * <li> 5 – The component was disposed
     * <li> 6 – The bundle was stopped
     * </ul>
     */
    public void deactivate(final int reason) {
        this.bundleContext = null;
        // deallocate resources here that are no longer needed and
        // should be reset when activating this binding again
    }


    /**
     * @{inheritDoc}
     */
    @Override
    protected long getRefreshInterval() {
        return refreshInterval;
    }

    /**
     * @{inheritDoc}
     */
    @Override
    protected String getName() {
        return "CSAS Refresh Service";
    }

    /**
     * @{inheritDoc}
     */
    @Override
    protected void execute() {
        // the frequently executed code (polling) goes here ...
        logger.debug("execute() method is called!");

        if (!bindingsExist()) {
            logger.debug("There is no existing CSAS binding configuration => refresh cycle aborted!");
            return;
        }

        refreshToken();

        for (final CSASBindingProvider provider : providers) {
            for (final String itemName : provider.getItemNames()) {
                String balance = getBalance(provider.getItemId(itemName));
                if (!balance.equals(provider.getItemState(itemName))) {
                    eventPublisher.postUpdate(itemName, new StringType(balance));
                    provider.setItemState(itemName, balance);
                }
            }
        }

    }

    private String getBalance(String accountId) {

        if (accountId.equals("ibod"))
        {
            return getLoyaltyBalance();
        }
        else {
            return getAccountBalance(accountId);
        }
    }

    private String getLoyaltyBalance() {
        String url = null;

        try {
            url = NETBANKING_V3 + "cz/my/contracts/loyalty";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getLoyalty: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            return jobject.get("pointsCount").getAsString();
        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS token: " + e.toString());
        }
        return "";
    }

    private String DoNetbankingRequest(String url) throws Exception {
        URL cookieUrl = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) cookieUrl.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("WEB-API-key", webAPIKey);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);

        InputStream response = connection.getInputStream();
        return readResponse(response);
    }

    private String getAccountBalance(String accountId) {
        String url = null;

        try {
            url = NETBANKING_V3 + "my/accounts/" + accountId + "/balance";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getBalance: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            jobject = jobject.get("balance").getAsJsonObject();
            String value = jobject.get("value").getAsString();
            String currency = jobject.get("currency").getAsString();
            int precision = jobject.get("precision").getAsInt();
            int places = value.length();

            String balance = value.substring(0, places - precision) + "." + value.substring(places - precision) + " " + currency;
            logger.debug("CSAS Balance: " + balance);
            return formatMoney(balance);
        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS token: " + e.toString());
        }
        return "";
    }

    private String formatMoney(String balance) {
        int len = balance.length();
        int dec = balance.indexOf('.');
        String newBalance = "";
        if (dec >= 0) {
            len = dec;
            newBalance = balance.substring(dec);
        }

        int j = 0;
        for (int i = len - 1; i >= 0; i--) {
            char c = balance.charAt(i);
            newBalance = c + newBalance;
            if (++j % 3 == 0 && i > 0 && balance.charAt(i - 1) != '-')
                newBalance = " " + newBalance;
        }
        return newBalance;
    }


    private void getTransactions(String accountId) {

        String url = null;

        try {
            url = NETBANKING_V3 + "my/accounts/" + accountId + "/transactions?dateStart=2016-08-01T00:00:00+02:00&dateEnd=2016-08-31T00:00:00+02:00";

            String line = DoNetbankingRequest(url);
            logger.info("CSAS getTransactions: " + line);

            /*
            JsonObject jobject = parser.parse(line).getAsJsonObject();
            jobject = jobject.get("balance").getAsJsonObject();
            String value = jobject.get("value").getAsString();
            String currency = jobject.get("currency").getAsString();
            int precision = jobject.get("precision").getAsInt();
            int places = value.length();

            String balance = value.substring(0, places - precision) + "." + value.substring(places - precision) + " " + currency;
            logger.debug("CSAS Balance: " + balance);
            //NumberFormat.getNumberInstance(Locale.US).format(balance);
            return balance;*/
        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS token: " + e.toString());
        }
    }

    private void getCards() {

        String url = null;

        try {
            url = NETBANKING_V3 + "my/cards";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getCards: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            JsonArray jarray = jobject.get("cards").getAsJsonArray();


            for (JsonElement je : jarray) {
                jobject = je.getAsJsonObject().get("mainAccount").getAsJsonObject();
                String id = jobject.get("id").getAsString();
                readAccount(id, jobject);
            }

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS token: " + e.toString());
        }
    }

    private void getPensions() {

        String url = null;

        try {
            url = NETBANKING_V3 + "cz/my/contracts/pensions";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getPensions: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            JsonArray jarray = jobject.get("pensions").getAsJsonArray();


            for (JsonElement je : jarray) {
                String id = je.getAsJsonObject().get("id").getAsString();
                String agreement = je.getAsJsonObject().get("agreementNumber").getAsString();
                logger.debug("Pension account: " + id);
                if (!accountList.containsKey(id))
                    accountList.put(id, "Pension agreement: " + agreement);
            }

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS token: " + e.toString());
        }
    }

    private void getBuildingSavings() {

        String url = null;

        try {
            url = NETBANKING_V3 + "my/contracts/buildings";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getBuildingSavings: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            JsonArray jarray = jobject.get("buildings").getAsJsonArray();

            for (JsonElement je : jarray) {
                jobject = je.getAsJsonObject();
                String id = jobject.get("id").getAsString();
                readAccount(id, jobject);
            }

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS token: " + e.toString());
        }
    }

    private void readAccount(String id, JsonObject jobject) {
        String number = jobject.get("accountno").getAsJsonObject().get("number").getAsString();
        String bankCode = jobject.get("accountno").getAsJsonObject().get("bankCode").getAsString();
        if (!accountList.containsKey(id))
            accountList.put(id, "Account: " + number + "/" + bankCode);
    }

    private void listUnboundAccounts() {
        StringBuilder sb = new StringBuilder();
        Iterator it = accountList.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry) it.next();
            String id = (String) pair.getKey();
            String acc = (String) pair.getValue();
            if (!isBound(id))
                sb.append("\t").append(acc).append(" Id: ").append(id).append("\n");
        }
        if (sb.length() > 0) {
            logger.info("Found unbound CSAS account(s): \n" + sb.toString());
        }
    }


    private void getAccounts() {

        String url = null;

        try {
            url = NETBANKING_V3 + "my/accounts";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getAccounts: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            JsonArray jarray = jobject.get("accounts").getAsJsonArray();

            for (JsonElement je : jarray) {
                jobject = je.getAsJsonObject();
                String id = jobject.get("id").getAsString();
                readAccount(id, jobject);
            }
        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS token: " + e.toString());
        }
    }

    private boolean isBound(String id) {

        for (final CSASBindingProvider provider : providers) {
            for (final String name : provider.getItemNames()) {
                String type = provider.getItemId(name);
                if (type.equals(id))
                    return true;
            }
        }
        return false;
    }

    /**
     * @{inheritDoc}
     */
    @Override
    protected void internalReceiveCommand(String itemName, Command command) {
        // the code being executed when a command was sent on the openHAB
        // event bus goes here. This method is only called if one of the
        // BindingProviders provide a binding for the given 'itemName'.
        logger.debug("internalReceiveCommand({},{}) is called!", itemName, command);
    }

    /**
     * @{inheritDoc}
     */
    @Override
    protected void internalReceiveUpdate(String itemName, State newState) {
        // the code being executed when a state was sent on the openHAB
        // event bus goes here. This method is only called if one of the
        // BindingProviders provide a binding for the given 'itemName'.
        logger.debug("internalReceiveUpdate({},{}) is called!", itemName, newState);
    }

}
