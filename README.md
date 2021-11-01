# azure-connector-public
A public version of the solution that I created while working for Ying Bi Lie. For security reasons, some lines of code have been redacted.

Wordpress Plugin
----------------
Deployed to the WordPress website to call the Azure API to process a purchase.
The action 'azc_send_user_request' must be called from a part of the code that runs after the purchase is complete and contains the purchase information. 

Azure Function
--------------
Deployed as an Azure Function to add the required data to the Azure SQL Database.
