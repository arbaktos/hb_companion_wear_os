// Thin HTTPS client. Requests proxy through Garmin Connect on the phone;
// TLS terminates against the PUBLIC (Let's Encrypt) endpoint :8443 — Garmin
// has no cert-pinning API, which is why that endpoint exists.
// BASE_URL + TOKEN come from Secrets.mc (gitignored; see Secrets.mc.example).

import Toybox.Lang;
import Toybox.Communications;

module BabyApi {

    function request(method, path) {
        var httpMethod = method.equals("POST")
            ? Communications.HTTP_REQUEST_METHOD_POST
            : Communications.HTTP_REQUEST_METHOD_GET;
        var options = {
            :method => httpMethod,
            :headers => {
                "Authorization" => "Bearer " + Secrets.TOKEN
            },
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
        };
        Communications.makeWebRequest(
            Secrets.BASE_URL + path, null, options, new Lang.Method(BabyApi, :onResponse)
        );
    }

    function onResponse(responseCode, data) {
        if (responseCode == 200 && data instanceof Lang.Dictionary) {
            Baby.onStatus(data);
        } else {
            Baby.onFailure(responseCode);
        }
    }
}
