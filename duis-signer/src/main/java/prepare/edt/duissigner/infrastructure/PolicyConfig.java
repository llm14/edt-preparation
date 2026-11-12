package prepare.edt.duissigner.infrastructure;

import io.smallrye.config.ConfigMapping;
import java.util.List;
import java.util.Map;

/**
 * The SRV allowlist of each User Role (prefix "edt.policy"). PLACEHOLDER values until DCC provides the real ones.
 *
 * In application.properties, an SRV contains dots, so it is quoted as a map key:
 *   edt.policy.roles.other-user.user-id=90-B3-D5-1F-30-01-00-01
 *   edt.policy.roles.other-user.srvs."4.1.1"=ESME,GSME
 */
@ConfigMapping(prefix = "edt.policy")
public interface PolicyConfig {

    /** Keyed by role name: other-user, registered-supplier-agent. */
    Map<String, Role> roles();

    interface Role {

        /** The role's DUIS User ID: the originator of the Service Requests it sends. */
        String userId();

        /** SRV to the device types allowed for it. */
        Map<String, List<String>> srvs();
    }
}
