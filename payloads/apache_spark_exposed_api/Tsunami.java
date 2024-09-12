import io.github.pixee.security.HostValidator;
import io.github.pixee.security.Urls;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

public class Tsunami {

  public static void main(String[] args) throws Exception {

    // Create and set all-trusting host name verifier to avoid certificate issues
    HttpsURLConnection.setDefaultHostnameVerifier(new TsunamiHostnameVerifier());
    // Create HTTP request to resource
    URL url = Urls.create(args[0], Urls.HTTP_PROTOCOLS, HostValidator.DENY_COMMON_INFRASTRUCTURE_TARGETS);
    HttpURLConnection con = (HttpURLConnection) url.openConnection();
    con.getInputStream();
  }
}
