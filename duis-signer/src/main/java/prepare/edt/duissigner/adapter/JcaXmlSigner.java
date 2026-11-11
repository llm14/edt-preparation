package prepare.edt.duissigner.adapter;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import org.w3c.dom.Document;
import prepare.edt.duissigner.entity.UserRole;
import prepare.edt.duissigner.usecase.Signer;

/**
 * Signs a Service Request with an enveloped XML signature (XML DSig), ECDSA P-256 with SHA-256,
 * through the Java Cryptography Architecture (JCA).
 *
 * Nothing here knows where the keys are. Two JCA objects are handed in:
 *   - a KeyStore: gives the private key and the certificate of each role, by alias;
 *   - a provider name: the JCA provider that computes the signature.
 * Software keys: KeyStore type PKCS12, provider "SunEC" (both built into the JDK).
 * AWS KMS: KeyStore and provider from our own KMS provider. The private key is then only a handle.
 *
 * The XML signature itself is built by the JDK (javax.xml.crypto, provider "XMLDSig").
 * It calls Signature.getInstance(algorithm, provider) with the provider we put in the sign context,
 * and converts the ECDSA signature from DER to the r||s format XML signatures use.
 */
public final class JcaXmlSigner implements Signer {

    /** Sign-context property read by the JDK's XML DSig to choose the JCA Signature provider. */
    static final String SIGNATURE_PROVIDER_PROPERTY = "org.jcp.xml.dsig.internal.dom.SignatureProvider";

    private final KeyStore keyStore;
    private final char[] keyPassword;
    private final Map<UserRole, String> aliases;
    private final Provider signatureProvider;
    private final XMLSignatureFactory xmlSignatures = XMLSignatureFactory.getInstance("DOM");

    /**
     * @param keyStore              holds the key and certificate of each role
     * @param keyPassword           password of the private keys, or null when the keystore needs none (KMS)
     * @param aliases               the keystore alias of each role
     * @param signatureProviderName JCA provider for the Signature engine, for example "SunEC"
     */
    public JcaXmlSigner(KeyStore keyStore, char[] keyPassword, Map<UserRole, String> aliases,
                        String signatureProviderName) {
        this.keyStore = keyStore;
        this.keyPassword = keyPassword == null ? null : keyPassword.clone();
        this.aliases = Map.copyOf(aliases);
        this.signatureProvider = Security.getProvider(signatureProviderName);
        if (signatureProvider == null) {
            throw new IllegalArgumentException("JCA provider not installed: " + signatureProviderName);
        }
    }

    @Override
    public String sign(String unsignedXml, UserRole role) {
        try {
            String alias = aliases.get(role);
            PrivateKey key = (PrivateKey) keyStore.getKey(alias, keyPassword);
            X509Certificate certificate = (X509Certificate) keyStore.getCertificate(alias);
            if (key == null || certificate == null) {
                throw new IllegalStateException("no key or certificate for alias " + alias);
            }

            Document document = SecureXml.parse(unsignedXml);

            // What is signed: the whole document (URI ""), minus the signature itself (enveloped).
            Reference reference = xmlSignatures.newReference("",
                    xmlSignatures.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(xmlSignatures.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                    null, null);

            SignedInfo signedInfo = xmlSignatures.newSignedInfo(
                    xmlSignatures.newCanonicalizationMethod(
                            CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                    xmlSignatures.newSignatureMethod(SignatureMethod.ECDSA_SHA256, null),
                    List.of(reference));

            // The certificate goes into the signature, so the receiver knows which key to verify with.
            KeyInfoFactory keyInfos = xmlSignatures.getKeyInfoFactory();
            KeyInfo keyInfo = keyInfos.newKeyInfo(List.of(keyInfos.newX509Data(List.of(certificate))));

            DOMSignContext context = new DOMSignContext(key, document.getDocumentElement());
            context.setProperty(SIGNATURE_PROVIDER_PROPERTY, signatureProvider);

            XMLSignature signature = xmlSignatures.newXMLSignature(signedInfo, keyInfo);
            signature.sign(context);

            return SecureXml.write(document);
        } catch (Exception e) {
            throw new IllegalStateException("signing as " + role + " failed: " + e.getMessage(), e);
        }
    }
}
