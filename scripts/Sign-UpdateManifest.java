import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Base64;

/** Local signing helper. Password is read only from the process environment. */
public class SignUpdateManifest {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) throw new IllegalArgumentException("Expected keystore, payload and output paths, optional test alias");
        String alias = args.length == 4 ? args[3] : "quietscreen";
        char[] password = System.getenv("QUIETSCREEN_RELEASE_PASSWORD").toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(Path.of(args[0]))) { store.load(input, password); }
            byte[] payload = Files.readAllBytes(Path.of(args[1]));
            if (payload.length > 4096) throw new IllegalArgumentException("Payload too large");
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign((PrivateKey)store.getKey(alias, password));
            signer.update(payload);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(store.getCertificate(alias));
            verifier.update(payload);
            if (!verifier.verify(signature)) throw new SecurityException("Signature verification failed");
            String envelope = "QUIETSCREEN_SIGNED_UPDATE_V1\n" + Base64.getEncoder().encodeToString(payload) + "\n" + Base64.getEncoder().encodeToString(signature) + "\n";
            Files.writeString(Path.of(args[2]), envelope, StandardCharsets.US_ASCII);
        } finally { java.util.Arrays.fill(password, '\0'); }
    }
}
