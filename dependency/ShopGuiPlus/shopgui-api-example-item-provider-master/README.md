# MyItems - Example plugin using ShopGUI+'s API
This repository contains an example Spigot plugin, MyItems, created with use of [ShopGUI+'s API](https://github.com/brcdev-minecraft/shopgui-api).
It shows how to correctly implement an item provider which will be registered in ShopGUI+ and used for selling & 
buying custom items.

## Building
```
./mvnw clean package
```

## Using
After you build the artifact using Maven command above, you can drop in the plugin to the `plugins` directory of your server along with regular ShopGUI+ plugin and see how it works.

You can expect this kind of messages during server startup:
```
[00:06:34] [Server thread/INFO]: [MyItems] Enabling MyItems v1.1.0
[00:06:34] [Server thread/INFO]: [MyItems] ShopGUI+ detected.
...
[00:06:34] [Server thread/INFO]: [ShopGUIPlus] Enabling ShopGUIPlus v1.73.1
...
[00:06:35] [Server thread/INFO]: Done (13.079s)! For help, type "help"
...
[00:06:38] [Server thread/INFO]: [ShopGUIPlus] Registered item provider 'MyItems'.
[00:06:38] [Server thread/INFO]: [MyItems] Registered item provider in ShopGUI+!
...
[00:06:38] [Server thread/INFO]: [ShopGUIPlus] Enabled item provider for MyItems.
```

It means that the MyItems plugin successfully registered its item provider in ShopGUI+ and can be used in shops.