/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 * <p>
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.csas.internal;

import org.openhab.binding.csas.CSASBindingProvider;
import org.openhab.core.binding.BindingConfig;
import org.openhab.core.items.Item;
import org.openhab.core.library.items.StringItem;
import org.openhab.model.item.binding.AbstractGenericBindingProvider;
import org.openhab.model.item.binding.BindingConfigParseException;


/**
 * This class is responsible for parsing the binding configuration.
 *
 * @author Ondrej Pecta
 * @since 1.9.0
 */
public class CSASGenericBindingProvider extends AbstractGenericBindingProvider implements CSASBindingProvider {

    /**
     * {@inheritDoc}
     */
    public String getBindingType() {
        return "csas";
    }

    /**
     * @{inheritDoc}
     */
    @Override
    public void validateItemType(Item item, String bindingConfig) throws BindingConfigParseException {
        if (!(item instanceof StringItem)) {
            throw new BindingConfigParseException("item '" + item.getName()
                    + "' is of type '" + item.getClass().getSimpleName()
                    + "', only StringItems are allowed - please check your *.items configuration");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processBindingConfiguration(String context, Item item, String bindingConfig) throws BindingConfigParseException {
        super.processBindingConfiguration(context, item, bindingConfig);

        String id = bindingConfig;
        if (id.contains("#")) {
            int pos = id.indexOf('#');
            id = id.substring(0, pos);
        }
        CSASBindingConfig config;

        if (bindingConfig.endsWith("#disposable") || id.equals(bindingConfig)) {
            config = new CSASBindingConfig(id, bindingConfig.endsWith("#disposable") ? CSASItemType.DISPOSABLE_BALANCE : CSASItemType.BALANCE);
        } else {
            config = new CSASBindingConfig(id, CSASItemType.TRANSACTION, Integer.parseInt(bindingConfig.replace(id + "#", "")));
        }
        addBindingConfig(item, config);
    }

    public String getItemId(String itemName) {
        final CSASBindingConfig config = (CSASBindingConfig) this.bindingConfigs.get(itemName);
        return config != null ? (config.getId()) : null;
    }

    public CSASItemType getItemType(String itemName) {
        final CSASBindingConfig config = (CSASBindingConfig) this.bindingConfigs.get(itemName);
        return config != null ? (config.getItemType()) : null;
    }

    public int getTransactionId(String itemName) {
        final CSASBindingConfig config = (CSASBindingConfig) this.bindingConfigs.get(itemName);
        return config != null ? (config.getTransactionId()) : null;
    }

    /**
     * This is a helper class holding binding specific configuration details
     *
     * @author Ondrej Pecta
     * @since 1.9.0
     */
    class CSASBindingConfig implements BindingConfig {
        // put member fields here which holds the parsed values

        private String id;
        private CSASItemType balanceType;

        private int transactionId;

        CSASBindingConfig(String id, CSASItemType balanceType) {
            this.id = id;
            this.balanceType = balanceType;
        }

        CSASBindingConfig(String id, CSASItemType balanceType, int transactionId) {
            this.id = id;
            this.balanceType = balanceType;
            this.transactionId = transactionId;
        }

        public String getId() {
            return id;
        }

        public CSASItemType getItemType() {
            return balanceType;
        }

        public int getTransactionId() {
            return transactionId;
        }
    }


}
