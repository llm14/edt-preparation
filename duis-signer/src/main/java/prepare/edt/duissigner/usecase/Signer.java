package prepare.edt.duissigner.usecase;

import prepare.edt.duissigner.entity.UserRole;

/**
 * Port: signs a Service Request XML with the key of a User Role and returns the signed XML.
 *
 * The use case does not know how: a software key for tests, AWS KMS (Floci locally), or later an HSM.
 * Each is an adapter implementing this interface, chosen by configuration.
 */
public interface Signer {

    String sign(String unsignedXml, UserRole role);
}
