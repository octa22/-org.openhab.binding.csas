# org.openhab.binding.csas

This binding brings Ceska sporitelna (CSAS) integration with OpenHAB1.x
The binding supports only read-only operations - getting product balances (including iBOD) and their transactions (where applicable) 

Based on info found here: https://developers.csas.cz/

# build
copy __org.openhab.binding.csas__ directory to __binding__ directory of OpenHAB source code (https://github.com/openhab/openhab)

build using maven (__mvn clean install__ or __mvn clean package__)

# install
copy target file __org.openhab.binding.csas*.jar__ to __addons__ directory of OpenHAB distribution

# usage
The binding lists all products bound with your account upon inicialization:
```
2017-03-12 15:39:11.988 [INFO ] [.o.b.csas.internal.CSASBinding] - Found unbound CSAS account(s): 
	Insurance: 5505321718 (Flexibilni pojisteni) Id: 92BF61CCA04C81DBF728F55B11CA47B32CF40ABC
```
These account IDs are used later in items file configuration.
Furthermore you can specify disposable balance by adding __#disposable__ suffix to your item configuration (defaut balance is accountable where blocked transactions are not yet projected) 

#configuration
```
######################## CSAS Binding ###########################

# CSAS clientId
csas:clientId={client id}

# CSAS clientSecret
csas:clientSecret={client secret}

# CSAS code
csas:code={csas code}

# CSAS refresh token
csas:refreshToken={refresh token}

# CSAS webAPI key
csas:webAPIKey=0e62d144-311a-4bf6-9868-64a3b52de4c9

# How many days in history is used for getting transactions. if no transactions found (or too few) transaction positions could be blank. Maximum is 60 days.
csas:history=14
```

#items example file
```
String CSASBalance "Bezny ucet [%s]" { csas="CCFB2302709618537C2C22BDBC0445A9EAE4F413" }
String CSASCreditBalance "Kreditni ucet [%s]" { csas="AD65B3F25E1145C0FD9A22A9E1477DC05F9A44B5#disposable" }
String CSASBuildingSavingBalance     "Stavebko [%s]"  { csas="D265F4F4D23EFB5D83622B27B5A3477E25769CC7" }
```

for getting transactions use this notation - the latest transactions are first, hash with number identifies the position 
```
String CSASCredTransaction1 "1. [%s]" { csas="AD65B3F25E1145C0FD9A22A9E1477DC05F9A44B5#1" }
String CSASCredTransaction2 "2. [%s]" { csas="AD65B3F25E1145C0FD9A22A9E1477DC05F9A44B5#2" }
String CSASCredTransaction3 "3. [%s]" { csas="AD65B3F25E1145C0FD9A22A9E1477DC05F9A44B5#3" }
```

for getting transaction detail (variable symbol, description, party, information) use this notation
```
String CSASCredTransaction1party "Party [%s]" { csas="AD65B3F25E1145C0FD9A22A9E1477DC05F9A44B5#1.party" }
String CSASCredTransaction1info "Info [%s]"  { csas="AD65B3F25E1145C0FD9A22A9E1477DC05F9A44B5#1.info" }
String CSASCredTransaction1desc "Desc. [%s]"  { csas="AD65B3F25E1145C0FD9A22A9E1477DC05F9A44B5#1.description" }
String CSASCredTransaction1vs "VS [%s]"  { csas="AD65B3F25E1145C0FD9A22A9E1477DC05F9A44B5#1.vs" }
```

#rule example file
```
// CSAS balance bound to Prowl action binding
rule "CSAS Balance changed"
when
  Item CSASBalance changed
then
		pushNotification("CSAS Balance", "Balance changed to " + CSASBalance.state.toString)
end

rule "CSAS Credit Balance changed"
when
  Item CSASCreditBalance changed
then
		pushNotification("CSAS Credit Balance", "Credit balance changed to " + CSASCreditBalance.state.toString)
end
```
