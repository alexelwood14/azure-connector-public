<?php
/**
 *Plugin Name: azure-connector
 *Description: Allows a purchase to automatically create a user within Azure
 *Author: Alex Elwood
 **/

function azc_send_request($message, $debug)
{
    $curl = curl_init();
    // ! Redacted in public version !
    $url = "";
    if ($debug) {
        $url .= "/debug";
    }
    // ! Redacted in public version !
    $url .= "";
    curl_setopt($curl, CURLOPT_URL, $url);
    curl_setopt($curl, CURLOPT_RETURNTRANSFER, 1);
    curl_setopt($curl, CURLOPT_POST, 1);
    curl_setopt($curl, CURLOPT_POSTFIELDS, json_encode($message));
    // ! Redacted in public version !
    curl_setopt($curl, CURLOPT_HTTPHEADER, array(
        'Content-Type: application/json',
    ));
    $output = curl_exec($curl);
    curl_close($curl);
    return $output;
}
add_action('azc_send_debug_message', 'azc_send_request', 10, 2);

function azc_send_user_request($purchase_data)
{
    // When a Microsoft email is not supplied then use the edd_email field
    if (is_null($purchase_data['cart_details']['ceddcf-field-8-1'])) {
        $email = $purchase_data['post_data']['edd_email'];
    }
    else {
        $email = $purchase_data['post_data']['ceddcf-field-8-1'];
    }

    // Need the correct active directory user principle name format
    $principle = str_replace('@', '_', $email);

    // Prepare data to send to Logic App
    $request_data = array(
        'email' => $email,
        'givenName' => $purchase_data['post_data']['edd_first'],
        'surname' => $purchase_data['post_data']['edd_last'],
        'business' => $purchase_data['post_data']['ceddcf-field-1-1'],
        'business_sect' => rtrim($purchase_data['post_data']['ceddcf-field-3-1'], "\r\n"),
        'business_type' => rtrim($purchase_data['post_data']['ceddcf-field-2-1'], "\r\n"),
        'address' => $purchase_data['post_data']['card_address'] . ', ' . $purchase_data['post_data']['card_address_2'] . ', ' . $purchase_data['post_data']['card_city'],
        'state' => $purchase_data['post_data']['card_state'],
        'postcode' => $purchase_data['post_data']['card_zip'],
        'country' => $purchase_data['post_data']['billing_country'],
        'phone' => $purchase_data['post_data']['ceddcf-field-5-1'],
        'license' => $purchase_data['cart_details'][0]['name'],
        'principle' => $principle
    );
    azc_send_request($request_data, FALSE);
}
add_action('azc_send_user_request', 'azc_send_user_request', 10, 1);
