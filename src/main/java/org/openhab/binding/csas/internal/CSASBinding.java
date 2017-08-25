/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 * <p>
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.csas.internal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.lang.StringUtils;
import org.openhab.binding.csas.CSASBindingProvider;
import org.openhab.binding.csas.internal.model.*;
import org.openhab.binding.csas.internal.model.response.*;
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
    private int historyInterval = 14;

    //Gson parser
    private Gson gson = new Gson();

    //Account list
    HashMap<String, String> accountList = new HashMap<>();

    //IbanList
    HashMap<String, String> ibanList = new HashMap<>();

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

            CSASRefreshTokenResponse resp = gson.fromJson(line, CSASRefreshTokenResponse.class);
            accessToken = resp.getAccessToken();

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

        if (configuration != null) {
            String refreshIntervalString = (String) configuration.get("refresh");
            if (StringUtils.isNotBlank(refreshIntervalString)) {
                refreshInterval = Long.parseLong(refreshIntervalString);
            }

            String historyIntervalString = (String) configuration.get("history");
            if (StringUtils.isNotBlank(historyIntervalString)) {
                historyInterval = Integer.parseInt(historyIntervalString);
                historyInterval = (historyInterval > 60) ? 60 : historyInterval;
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

    }

    /**
     * Called by the SCR when the configuration of a binding has been changed through the ConfigAdmin service.
     *
     * @param configuration Updated configuration properties
     */
    public void modified(final Map<String, Object> configuration) {
        // update the internal configuration accordingly
        if (configuration != null) {
            readConfiguration(configuration);
            execute();
        }
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
        accountList.clear();
        ibanList.clear();
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

        if (accessToken.equals("")) {
            refreshToken();
            if (accessToken.equals(""))
                return;
            getAccounts();
            getCards();
            getBuildingSavings();
            getPensions();
            getInsurances();
            getSecurities();
            listUnboundAccounts();

        } else
            refreshToken();

        HashMap<String, ArrayList<CSASSimpleTransaction>> transactionsList = new HashMap<>();

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

    private String getTransactionValue(String itemName, HashMap<String, ArrayList<CSASSimpleTransaction>> transactionsList, CSASBindingProvider provider) {
        String accountId = provider.getItemId(itemName);
        if (!transactionsList.containsKey(accountId)) {
            ArrayList<CSASSimpleTransaction> list = new ArrayList<>();
            list.addAll(getReservations(accountId));
            list.addAll(getTransactions(accountId));
            transactionsList.put(accountId, list);
        }
        int id = provider.getTransactionId(itemName);
        if (id > transactionsList.get(accountId).size())
            return "";


        String result = "";
        CSASItemType type = provider.getItemType(itemName);
        switch (type) {
            case TRANSACTION_BALANCE:
                result = transactionsList.get(accountId).get(id - 1).getBalance();
                break;
            case TRANSACTION_INFO:
                result = transactionsList.get(accountId).get(id - 1).getAccountPartyInfo();
                break;
            case TRANSACTION_DESCRIPTION:
                result = transactionsList.get(accountId).get(id - 1).getDescription();
                break;
            case TRANSACTION_VS:
                result = transactionsList.get(accountId).get(id - 1).getVariableSymbol();
                break;
            case TRANSACTION_PARTY:
                result = transactionsList.get(accountId).get(id - 1).getAccountPartyDescription();
                break;
        }
        return result;
    }

    private String getIbanFromAccountId(String accountId) {
        if (ibanList.containsKey(accountId)) {
            return ibanList.get(accountId);
        }

        logger.error("Cannot get IBAN for account: " + accountId);
        return "";
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

            CSASLoyaltyResponse resp = gson.fromJson(line, CSASLoyaltyResponse.class);
            return resp.getPointsCount() != null ? formatMoney(resp.getPointsCount()) : "N/A";
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

            CSASAccountBalanceResponse resp = gson.fromJson(line, CSASAccountBalanceResponse.class);
            CSASAmount bal;
            if (balanceType.equals(CSASItemType.BALANCE))
                bal = resp.getBalance();
            else
                bal = resp.getDisposable();

            String balance = readBalance(bal);
            logger.debug("CSAS Balance: " + balance);
            return formatMoney(balance);
        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS balance: " + e.toString());
        }
        return "";
    }

    private String readBalance(CSASAmount balance) {
        String value = balance.getValue();
        String currency = balance.getCurrency();

        int precision = balance.getPrecision();
        int places = value.length();

        return (precision == 0) ? value + ".00 " + currency : value.substring(0, places - precision) + "." + value.substring(places - precision) + " " + currency;
    }

    private String safeGetString(JsonObject jobject, String value) {
        if (jobject == null || jobject.isJsonNull() || !jobject.has(value)) return "null";
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


    private ArrayList<CSASSimpleTransaction> getTransactions(String accountId) {

        String url = null;
        ArrayList<CSASSimpleTransaction> transactionsList = new ArrayList<>();

        SimpleDateFormat requestFormat = new SimpleDateFormat("yyyy-MM-dd");

        try {
            url = NETBANKING_V3 + "cz/my/accounts/" + getIbanFromAccountId(accountId) + "/transactions?dateStart=" + requestFormat.format(addDays(new Date(), -historyInterval)) + "T00:00:00+01:00&dateEnd=" + requestFormat.format(new Date()) + "T00:00:00+01:00";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getTransactions: {}", line);

            CSASTransactionsResponse resp = gson.fromJson(line, CSASTransactionsResponse.class);
            if (resp.getTransactions() != null) {
                for (CSASTransaction tran : resp.getTransactions()) {
                    transactionsList.add(createTransaction(tran));
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

    private ArrayList<CSASSimpleTransaction> getReservations(String accountId) {

        String url = null;
        ArrayList<CSASSimpleTransaction> reservationsList = new ArrayList<>();

        try {
            url = NETBANKING_V3 + "my/accounts/" + accountId + "/reservations";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getReservations: " + line);

            CSASReservationsResponse resp = gson.fromJson(line, CSASReservationsResponse.class);
            if (resp.getReservations() != null) {
                for (CSASReservation reservation : resp.getReservations()) {
                    reservationsList.add(createReservation(reservation));
                }
            }

            logger.trace("Reservations: " + reservationsList.toString());
            return reservationsList;

        } catch (MalformedURLException e) {
            logger.error("The URL '" + url + "' is malformed: " + e.toString());
        } catch (Exception e) {
            logger.error("Cannot get CSAS reservations: " + e.toString());
        }
        return reservationsList;
    }

    private CSASSimpleTransaction createTransaction(CSASTransaction csasTran) throws ParseException {

        SimpleDateFormat myUTCFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        SimpleDateFormat requiredFormat = new SimpleDateFormat("dd.MM.yyyy");

        CSASSimpleTransaction tran = new CSASSimpleTransaction();

        try {
            CSASAmount amount = csasTran.getAmount();

            Date date = myUTCFormat.parse(csasTran.getBookingDate());
            String shortDate = requiredFormat.format(date);

            String balance = formatMoney(readBalance(amount)) + " " + shortDate;
            String description = csasTran.getDescription();
            tran.setBalance(balance);

            if (description != null) {
                tran.setDescription(description);
            }

            String variableSymbol = csasTran.getVariableSymbol();
            if (variableSymbol != null) {
                tran.setVariableSymbol(variableSymbol);
            }

            CSASAccountParty party = csasTran.getAccountParty();
            if (party == null) {
                return tran;
            }
            if (party.getAccountPartyInfo() != null) {
                String accountPartyInfo = party.getAccountPartyInfo();
                tran.setAccountPartyInfo(accountPartyInfo);
            }
            if (party.getAccountPartyDescription() != null) {
                String accountPartyDescription = party.getAccountPartyDescription();
                tran.setAccountPartyDescription(accountPartyDescription);
            }
        } catch (Exception ex) {
            logger.error(ex.toString());
        }
        return tran;
    }

    private CSASSimpleTransaction createReservation(CSASReservation reservation) throws ParseException {

        SimpleDateFormat myUTCFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        SimpleDateFormat requiredFormat = new SimpleDateFormat("dd.MM.yyyy");

        Date date = myUTCFormat.parse(reservation.getCreationDate());
        String shortDate = requiredFormat.format(date);

        CSASSimpleTransaction tran = new CSASSimpleTransaction();

        try {
            CSASAmount amount = reservation.getAmount();

            String balance = "RES " + formatMoney(readBalance(amount)) + " " + shortDate;

            tran.setBalance(balance);

            if (reservation.getDescription() != null) {
                tran.setDescription(reservation.getDescription());
            }

            String variableSymbol = "";
            tran.setVariableSymbol(variableSymbol);

            if (reservation.getMerchantAddress() != null) {
                tran.setAccountPartyDescription(reservation.getMerchantAddress());
            }
            if (reservation.getMerchantName() != null) {
                tran.setAccountPartyInfo(reservation.getMerchantName());
            }
        } catch (Exception ex) {
            logger.error(ex.toString());
        }
        return tran;
    }

    private void getCards() {

        String url = null;

        try {
            url = NETBANKING_V3 + "my/cards";

            String line = DoNetbankingRequest(url);
            logger.debug("CSAS getCards: " + line);

            CSASCardsResponse resp = gson.fromJson(line, CSASCardsResponse.class);
            if (resp.getCards() != null) {
                for (CSASCard card : resp.getCards()) {
                    CSASAccount cardAccount = card.getMainAccount();
                    if (cardAccount != null) {
                        readAccount(cardAccount.getId(), cardAccount.getAccountno());
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

            CSASSecuritiesResponse resp = gson.fromJson(line, CSASSecuritiesResponse.class);
            if (resp.getSecuritiesAccounts() != null) {
                for (CSASSecuritiesAccount mainAccount : resp.getSecuritiesAccounts()) {
                    String id = mainAccount.getId();
                    String accountno = mainAccount.getAccountno();
                    if (!accountList.containsKey(id)) {
                        accountList.put(id, "Securities account: " + accountno);
                    }
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

            CSASPensions resp = gson.fromJson(line, CSASPensions.class);
            if (resp.getPensions() != null) {
                for (CSASAgreement agreement : resp.getPensions()) {
                    String id = agreement.getId();
                    String number = agreement.getAgreementNumber();
                    if (!accountList.containsKey(id))
                        accountList.put(id, "Pension agreement: " + number);
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

            CSASBuildingsResponse resp = gson.fromJson(line, CSASBuildingsResponse.class);
            if (resp.getBuildings() != null) {
                for (CSASAccount account : resp.getBuildings()) {
                    readAccount(account.getId(), account.getAccountno());
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

            CSASInsurancesResponse resp = gson.fromJson(line, CSASInsurancesResponse.class);
            if (resp.getInsurances() != null) {
                for (CSASInsurance insurance : resp.getInsurances()) {
                    String id = insurance.getId();
                    String policyNumber = insurance.getPolicyNumber();
                    String productI18N = insurance.getProductI18N();
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

    private void readAccount(String id, CSASAccountNumber account) {
        if (account == null)
            return;

        String number = account.getNumber();
        String bankCode = account.getBankCode();
        String iban = account.getIban();
        if (!accountList.containsKey(id)) {
            accountList.put(id, "Account: " + number + "/" + bankCode);
            ibanList.put(id, iban);
        }
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

            CSASAccountsResponse resp = gson.fromJson(line, CSASAccountsResponse.class);
            if (resp.getAccounts() != null) {
                for (CSASAccount account : resp.getAccounts()) {
                    readAccount(account.getId(), account.getAccountno());
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
