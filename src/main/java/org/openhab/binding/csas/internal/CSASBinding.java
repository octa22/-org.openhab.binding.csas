/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 * <p>
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.csas.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang.StringUtils;
import org.openhab.binding.csas.CSASBindingProvider;
import org.openhab.core.binding.AbstractActiveBinding;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.apache.commons.lang.time.DateUtils.addDays;

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
     * -DarchetypeArtifactId=org.openhab.archetype.binding \
     * -DarchetypeVersion=1.8.0-SNAPSHOT -Dauthor="Ondrej Pecta" -Dversion=1.9.0 \
     * -DartifactId=org.openhab.binding.csas \
     * -Dpackage=org.openhab.binding.csas \
     * -Dbinding-name=CSAS
     */
    private BundleContext bundleContext;
    private ItemRegistry itemRegistry;

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

        setProperlyConfigured(true);
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
            accessToken = safeGetString(jobject, "access_token");

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
     *
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
     *
     * @param reason Reason code for the deactivation:<br>
     *               <ul>
     *               <li> 0 – Unspecified
     *               <li> 1 – The component was disabled
     *               <li> 2 – A reference became unsatisfied
     *               <li> 3 – A configuration was changed
     *               <li> 4 – A configuration was deleted
     *               <li> 5 – The component was disposed
     *               <li> 6 – The bundle was stopped
     *               </ul>
     */
    public void deactivate(final int reason) {
        this.bundleContext = null;
        // deallocate resources here that are no longer needed and
        // should be reset when activating this binding again
    }

    public void setItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    public void unsetItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = null;
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

        if (refreshToken.equals("")) {
            refreshToken();
            getAccounts();
            getCards();
            getBuildingSavings();
            getPensions();
            getInsurances();
            getSecurities();
            listUnboundAccounts();
            if (refreshToken.equals(""))
                return;
        } else
            refreshToken();

        HashMap<String, ArrayList<CSASTransaction>> transactionsList = new HashMap<>();

        for (final CSASBindingProvider provider : providers) {
            for (final String itemName : provider.getItemNames()) {
                State oldValue;
                State newValue;
                try {
                    oldValue = itemRegistry.getItem(itemName).getState();

                    if (provider.getItemType(itemName).equals(CSASItemType.DISPOSABLE_BALANCE) || provider.getItemType(itemName).equals(CSASItemType.BALANCE)) {
                        String balance = getBalance(provider.getItemId(itemName), provider.getItemType(itemName));
                        newValue = new StringType(balance);
                    } else {
                        newValue = new StringType(getTransactionValue(itemName, transactionsList, provider));
                    }
                    if (!oldValue.equals(newValue)) {
                        eventPublisher.postUpdate(itemName, newValue);
                    }
                } catch (ItemNotFoundException e) {
                    logger.error("Cannot find item " + itemName + " in item registry!");
                }
            }
        }

    }

    private String getTransactionValue(String itemName, HashMap<String, ArrayList<CSASTransaction>> transactionsList, CSASBindingProvider provider) {
        String iban = provider.getItemId(itemName);
        if (!transactionsList.containsKey(iban))
            transactionsList.put(iban, getTransactions(iban));
        int id = provider.getTransactionId(itemName);
        if (id > transactionsList.get(iban).size())
            return "";


        String result = "";
        CSASItemType type = provider.getItemType(itemName);
        switch (type) {
            case TRANSACTION_BALANCE:
                result = transactionsList.get(iban).get(id - 1).getBalance();
                break;
            case TRANSACTION_INFO:
                result = transactionsList.get(iban).get(id - 1).getAccountPartyInfo();
                break;
            case TRANSACTION_DESCRIPTION:
                result = transactionsList.get(iban).get(id - 1).getDescription();
                break;
            case TRANSACTION_VS:
                result = transactionsList.get(iban).get(id - 1).getVariableSymbol();
                break;
            case TRANSACTION_PARTY:
                result = transactionsList.get(iban).get(id - 1).getAccountPartyDescription();
                break;
        }
        return result;
    }

    private String getBalance(String accountId, CSASItemType balanceType) {

        if (accountId.equals("ibod")) {
            return getLoyaltyBalance();
        } else {
            return getAccountBalance(accountId, balanceType);
        }
    }

    private String getLoyaltyBalance() {
        String url = null;

        try {
            url = NETBANKING_V3 + "cz/my/contracts/loyalty";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getLoyalty: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            if (jobject.get("pointsCount") != null) {
                return formatMoney(safeGetString(jobject, "pointsCount"));
            } else
                return "N/A";
        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS loyalty points: " + e.toString());
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

    private String getAccountBalance(String accountId, CSASItemType balanceType) {
        String url = null;

        try {
            url = NETBANKING_V3 + "my/accounts/" + accountId + "/balance";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getBalance: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            if (balanceType.equals(CSASItemType.BALANCE))
                jobject = jobject.get("balance").getAsJsonObject();
            else
                jobject = jobject.get("disposable").getAsJsonObject();

            String balance = readBalance(jobject);

            logger.debug("CSAS Balance: " + balance);
            return formatMoney(balance);
        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS balance: " + e.toString());
        }
        return "";
    }

    private String readBalance(JsonObject jobject) {
        String value = safeGetString(jobject, "value");
        String currency = safeGetString(jobject, "currency");

        int precision = jobject.get("precision").getAsInt();
        int places = value.length();

        String balance = (precision == 0) ? value + ".00 " + currency : value.substring(0, places - precision) + "." + value.substring(places - precision) + " " + currency;
        return balance;
    }

    private String safeGetString(JsonObject jobject, String value) {
        if (jobject == null || jobject.isJsonNull() || jobject.get(value) == null) return "null";
        return (jobject.get(value).isJsonNull() ? "N/A" : jobject.get(value).getAsString());
    }

    private String formatMoney(String balance) {
        String newBalance = "";
        int len = balance.length();
        int dec = balance.indexOf('.');
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


    private ArrayList<CSASTransaction> getTransactions(String accountId) {

        String url = null;
        ArrayList<CSASTransaction> transactionsList = new ArrayList<>();

        SimpleDateFormat requestFormat = new SimpleDateFormat("yyyy-MM-dd");

        try {
            url = NETBANKING_V3 + "cz/my/accounts/" + accountId + "/transactions?dateStart=" + requestFormat.format(addDays(new Date(), -14)) + "T00:00:00+01:00&dateEnd=" + requestFormat.format(new Date()) + "T00:00:00+01:00";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getTransactions: " + line);
            JsonObject jobject = parser.parse(line).getAsJsonObject();
            if (jobject.get("transactions") != null) {

                JsonArray jarray = jobject.get("transactions").getAsJsonArray();

                for (JsonElement je : jarray) {
                    jobject = je.getAsJsonObject();
                    transactionsList.add(createTransaction(jobject));
                }
            }
            logger.trace("Transactions: " + transactionsList.toString());
            return transactionsList;

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS transactions: " + e.toString());
        }
        return transactionsList;
    }

    private CSASTransaction createTransaction(JsonObject jobject) throws ParseException {

        SimpleDateFormat myUTCFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        SimpleDateFormat requiredFormat = new SimpleDateFormat("dd.MM.yyyy");

        Date date = myUTCFormat.parse(safeGetString(jobject, "bookingDate"));
        String shortDate = requiredFormat.format(date);

        CSASTransaction tran = new CSASTransaction();

        if (jobject != null && !jobject.isJsonNull()) {
            try {
                JsonObject jamount = jobject.get("amount").getAsJsonObject();

                String balance = formatMoney(readBalance(jamount)) + " " + shortDate;
                String description = safeGetString(jobject, "description");
                tran.setBalance(balance);
                tran.setDescription(description);

                String variableSymbol = safeGetString(jobject, "variableSymbol");
                tran.setVariableSymbol(variableSymbol);

                if (jobject.get("accountParty") != null) {
                    JsonObject jparty = jobject.get("accountParty").getAsJsonObject();
                    String accountPartyDescription = safeGetString(jparty, "accountPartyDescription");
                    tran.setAccountPartyDescription(accountPartyDescription);

                    String accountPartyInfo = safeGetString(jparty, "accountPartyInfo");
                    tran.setAccountPartyInfo(accountPartyInfo);
                }
            } catch (Exception ex) {
                logger.error(ex.toString());
            }
        }
        return tran;
    }

    private void getCards() {

        String url = null;

        try {
            url = NETBANKING_V3 + "my/cards";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getCards: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            if (jobject.get("cards") != null) {
                JsonArray jarray = jobject.get("cards").getAsJsonArray();

                for (JsonElement je : jarray) {
                    jobject = je.getAsJsonObject().get("mainAccount").getAsJsonObject();
                    if (jobject != null) {
                        String id = safeGetString(jobject, "id");
                        readAccount(id, jobject);
                    }
                }
            }

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS cards: " + e.toString());
        }
    }

    private void getSecurities() {

        String url = null;

        try {
            url = NETBANKING_V3 + "my/securities";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getSecurities: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            if (jobject.get("securitiesAccounts") != null) {
                JsonArray jarray = jobject.get("securitiesAccounts").getAsJsonArray();

                for (JsonElement je : jarray) {
                    String id = safeGetString(je.getAsJsonObject(), "id");
                    String account = safeGetString(je.getAsJsonObject(), "accountno");
                    if (!accountList.containsKey(id))
                        accountList.put(id, "Securities account: " + account);
                }
            }

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS securities: " + e.toString());
        }
    }

    private void getPensions() {

        String url = null;

        try {
            url = NETBANKING_V3 + "cz/my/contracts/pensions";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getPensions: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            if (jobject.get("pensions") != null) {
                JsonArray jarray = jobject.get("pensions").getAsJsonArray();

                for (JsonElement je : jarray) {
                    String id = safeGetString(je.getAsJsonObject(), "id");
                    String agreement = safeGetString(je.getAsJsonObject(), "agreementNumber");
                    if (!accountList.containsKey(id))
                        accountList.put(id, "Pension agreement: " + agreement);
                }
            }

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS pensions: " + e.toString());
        }
    }

    private void getBuildingSavings() {

        String url = null;

        try {
            url = NETBANKING_V3 + "my/contracts/buildings";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getBuildingSavings: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            if (jobject.get("buildings") != null) {
                JsonArray jarray = jobject.get("buildings").getAsJsonArray();

                for (JsonElement je : jarray) {
                    jobject = je.getAsJsonObject();
                    if (jobject != null) {
                        String id = safeGetString(jobject, "id");
                        readAccount(id, jobject);
                    }
                }
            }

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS building savings: " + e.toString());
        }
    }

    private void getInsurances() {

        String url = null;

        try {
            url = NETBANKING_V3 + "my/contracts/insurances";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getInsurances: " + line);

            JsonObject jobject = parser.parse(line).getAsJsonObject();
            if (jobject.get("insurances") != null) {
                JsonArray jarray = jobject.get("insurances").getAsJsonArray();

                for (JsonElement je : jarray) {
                    jobject = je.getAsJsonObject();
                    String id = safeGetString(jobject, "id");
                    String policyNumber = safeGetString(jobject, "policyNumber");
                    String productI18N = safeGetString(jobject, "productI18N");
                    if (!accountList.containsKey(id))
                        accountList.put(id, "Insurance: " + policyNumber + " (" + productI18N + ")");
                }
            }

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS insurances: " + e.toString());
        }
    }


    private void readAccount(String id, JsonObject jobject) {
        String number = safeGetString(jobject.get("accountno").getAsJsonObject(), "number");
        String bankCode = safeGetString(jobject.get("accountno").getAsJsonObject(), "bankCode");
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
            if (jobject.get("accounts") != null) {
                JsonArray jarray = jobject.get("accounts").getAsJsonArray();

                for (JsonElement je : jarray) {
                    jobject = je.getAsJsonObject();
                    if (jobject != null) {
                        String id = safeGetString(jobject, "id");
                        readAccount(id, jobject);
                    }
                }
            }
        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS accounts: " + e.toString());
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
