package com.enterprise.security.userservice.service;

import com.enterprise.security.common.audit.Audited;
import com.enterprise.security.common.tenant.TenantContext;
import com.enterprise.security.userservice.domain.User;
import com.enterprise.security.userservice.dto.UpdateUserRequest;
import com.enterprise.security.userservice.repository.UserRepository;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @PostAuthorize evaluates the SpEL expression against the *returned* User, which is the only
     * way to enforce "same tenant as caller" here — the tenant of the target resource isn't known
     * until it's loaded. Ownership (#id == authentication.name) short-circuits the tenant check for
     * users looking up themselves.
     */
    @Transactional(readOnly = true)
    @PostAuthorize("hasRole('ADMIN') or hasRole('SUPPORT') "
            + "or returnObject.id == authentication.name "
            + "or returnObject.tenantId == authentication.token.claims['tenant']")
    public User getUser(String id) {
        return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    /** Coarse RBAC gate: only back-office roles may list every user in the caller's tenant. */
    @Transactional(readOnly = true)
    @Secured({"ROLE_ADMIN", "ROLE_SUPPORT"})
    public List<User> listUsersInCurrentTenant() {
        String tenant = TenantContext.get();
        return userRepository.findByTenantId(tenant);
    }

    /** Ownership OR admin: users may edit their own profile; only ADMIN may edit others. */
    @PreAuthorize("hasRole('ADMIN') or #id == authentication.name")
    @Audited(action = "USER_UPDATE")
    public User updateUser(String id, UpdateUserRequest request) {
        User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }
        if (request.department() != null) {
            user.setDepartment(request.department());
        }
        return userRepository.save(user);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "USER_DEACTIVATE")
    public void deactivateUser(String id) {
        User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        user.setActive(false);
        userRepository.save(user);
    }

    /**
     * Called only by other services over the client-credentials grant. Defense in depth: the
     * endpoint route is already restricted to ROLE_SERVICE in SecurityConfig, and Istio's
     * AuthorizationPolicy further restricts which mesh workload identities may reach this route at
     * all — this method-level check is the third, innermost layer.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public User getUserForServiceCall(String id) {
        return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }
}
