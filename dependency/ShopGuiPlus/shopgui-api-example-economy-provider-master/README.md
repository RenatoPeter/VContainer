# MyEconomy - Example plugin using ShopGUI+'s API
This repository contains an example Spigot plugin, MyEconomy, created with use of [ShopGUI+'s API](https://github.com/brcdev-minecraft/shopgui-api).
It shows how to correctly implement an in-memory economy provider which will be registered in ShopGUI+ and used for money transactions handling. 

Note: This is a very basic implementation of economy and shouldn't be used in production.

## Building
```
./mvnw clean package
```

## Using
After you build the artifact using Maven command above, you can drop in the plugin to the `plugins` directory of your server along with regular ShopGUI+ plugin and see how it works.

Next, make sure to add `CUSTOM` economy to your ShopGUI+ config.yml:
```
economyTypes:
  - VAULT
  - CUSTOM
```

You can expect this kind of messages during server startup:
```
[00:02:47] [Server thread/INFO]: [MyEconomy] Enabling MyEconomy v1.1.0
[00:02:47] [Server thread/INFO]: [MyEconomy] ShopGUI+ detected.
...
[00:02:47] [Server thread/INFO]: [ShopGUIPlus] Enabling ShopGUIPlus v1.73.1
...
[00:02:49] [Server thread/INFO]: Done (13.176s)! For help, type "help"
...
[00:02:52] [Server thread/INFO]: [ShopGUIPlus] Registered custom economy provider 'MyEconomy'.
[00:02:52] [Server thread/INFO]: [MyEconomy] Registered economy provider in ShopGUI+!
...
[00:02:52] [Server thread/INFO]: [ShopGUIPlus] MyEconomy economy enabled.
```

It means that the MyEconomy plugin successfully registered its economy provider in ShopGUI+ and can be used in all or
 just some of the shops.
