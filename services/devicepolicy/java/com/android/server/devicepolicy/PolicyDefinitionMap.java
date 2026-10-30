/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.devicepolicy;

import static com.android.server.devicepolicy.PolicyDefinition.APP_FUNCTIONS;
import static com.android.server.devicepolicy.PolicyDefinition.AUDIT_LOGGING;
import static com.android.server.devicepolicy.PolicyDefinition.COMMON_CRITERIA_MODE;
import static com.android.server.devicepolicy.PolicyDefinition.CONTENT_PROTECTION;
import static com.android.server.devicepolicy.PolicyDefinition.CROSS_PROFILE_WIDGET_PROVIDER;
import static com.android.server.devicepolicy.PolicyDefinition.GENERIC_ACCOUNT_MANAGEMENT_DISABLED;
import static com.android.server.devicepolicy.PolicyDefinition.GENERIC_APPLICATION_HIDDEN;
import static com.android.server.devicepolicy.PolicyDefinition.GENERIC_APPLICATION_RESTRICTIONS;
import static com.android.server.devicepolicy.PolicyDefinition.GENERIC_PACKAGE_UNINSTALL_BLOCKED;
import static com.android.server.devicepolicy.PolicyDefinition.GENERIC_PERMISSION_GRANT;
import static com.android.server.devicepolicy.PolicyDefinition.GENERIC_PERSISTENT_PREFERRED_ACTIVITY;
import static com.android.server.devicepolicy.PolicyDefinition.KEYGUARD_DISABLED_FEATURES;
import static com.android.server.devicepolicy.PolicyDefinition.LOCK_TASK;
import static com.android.server.devicepolicy.PolicyDefinition.MEMORY_TAGGING;
import static com.android.server.devicepolicy.PolicyDefinition.PACKAGES_SUSPENDED;
import static com.android.server.devicepolicy.PolicyDefinition.PASSWORD_COMPLEXITY;
import static com.android.server.devicepolicy.PolicyDefinition.PERMITTED_INPUT_METHODS;
import static com.android.server.devicepolicy.PolicyDefinition.PERSONAL_APPS_SUSPENDED;
import static com.android.server.devicepolicy.PolicyDefinition.POLICY_FLAG_GLOBAL_ONLY_POLICY;
import static com.android.server.devicepolicy.PolicyDefinition.POLICY_FLAG_INHERITABLE;
import static com.android.server.devicepolicy.PolicyDefinition.POLICY_FLAG_USER_RESTRICTION_POLICY;
import static com.android.server.devicepolicy.PolicyDefinition.RESET_PASSWORD_TOKEN;
import static com.android.server.devicepolicy.PolicyDefinition.SCREEN_CAPTURE_DISABLED;
import static com.android.server.devicepolicy.PolicyDefinition.SECURITY_LOGGING;
import static com.android.server.devicepolicy.PolicyDefinition.TRUE_MORE_RESTRICTIVE;
import static com.android.server.devicepolicy.PolicyDefinition.USB_DATA_SIGNALING;
import static com.android.server.devicepolicy.PolicyDefinition.USER_CONTROLLED_DISABLED_PACKAGES;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyIdentifiers;
import android.app.admin.PolicyKey;
import android.app.admin.UserRestrictionPolicyKey;
import android.app.admin.flags.Flags;
import android.os.UserManager;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.server.utils.Slogf;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

public class PolicyDefinitionMap {
    static final String TAG = "PolicyDefinitionMap";
    private final Map<String, PolicyDefinition<?>> mPolicyDefinitions;

    /**
     * The set of {@link PolicyDefinition} instances that represent "generic" policies. These
     * policies are not tied to a single, fixed key but can have multiple instances, often
     * distinguished by an additional key (e.g., package name for application restrictions).
     */
    private final Set<PolicyDefinition<?>> mGenericPolicyDefinitions;

    // The policies that are not yet supported by DevicePolicyEngine, thus don't have definition.
    private final Set<String> mLegacyPolicies = new HashSet<>();

    /**
     * Create the PolicyDefinitionMap with all supported policy definitions.
     *
     * The resulting map will contain both the policy definitions passed in plus
     * the policy definitions hard-coded in this class.
     */
    PolicyDefinitionMap(Collection<PolicyDefinition<?>> extraPolicyDefinitions) {
        this(merge(loadPolicyDefinitions(), extraPolicyDefinitions), loadGenericPolicies());
    }

    /**
     * Create the PolicyDefinitionMap with all supported policy definitions.
     *
     * The resulting map will contain both the policy definitions hard-coded in this class.
     */
    PolicyDefinitionMap() {
        this(Set.of());
    }

    /**
     * Allow overriding the policy definition for unit testing. Does not set any generic policy
     * definitions.
     */
    PolicyDefinitionMap(Map<String, PolicyDefinition<?>> policyDefinitions) {
        this(policyDefinitions, new HashSet<>());
    }

    /**
     * Creates a PolicyDefinitionMap.
     *
     * @param policyDefinitions A map of all policy identifiers to their definitions.
     * @param genericPolicyIdentifiers A set of policy identifiers that are considered generic.
     *     Every key in {@code genericPolicyIdentifiers} must be present as a key in {@code
     *     policyDefinitions}.
     */
    PolicyDefinitionMap(
            Map<String, PolicyDefinition<?>> policyDefinitions,
            Set<String> genericPolicyIdentifiers) {
        mPolicyDefinitions = Collections.unmodifiableMap(policyDefinitions);
        populateLegacyPolicies(mPolicyDefinitions);

        Set<PolicyDefinition<?>> genericPolicyDefinitions = new HashSet<>();
        for (String identifier : genericPolicyIdentifiers) {
            var definition = policyDefinitions.get(identifier);

            if (definition == null) {
                throw new IllegalArgumentException(
                        "Every value of `genericPolicyIdentifiers` must be a identifier in "
                                + "`policyDefinitions`, but \""
                                + identifier
                                + "\" was not found.");
            }
        }

        mGenericPolicyDefinitions = Collections.unmodifiableSet(genericPolicyDefinitions);
    }

    // TODO(b/277218360): Revisit policies that should be marked as global-only.
    private static Map<String, PolicyDefinition<?>> loadPolicyDefinitions() {
        Map<String, PolicyDefinition<?>> policyDefinitions = new HashMap<>();

        policyDefinitions.put(
                DevicePolicyIdentifiers.PERMISSION_GRANT_POLICY, GENERIC_PERMISSION_GRANT);
        policyDefinitions.put(DevicePolicyIdentifiers.SECURITY_LOGGING_POLICY, SECURITY_LOGGING);
        policyDefinitions.put(DevicePolicyIdentifiers.AUDIT_LOGGING_POLICY, AUDIT_LOGGING);
        policyDefinitions.put(DevicePolicyIdentifiers.LOCK_TASK_POLICY, LOCK_TASK);
        policyDefinitions.put(
                DevicePolicyIdentifiers.USER_CONTROL_DISABLED_PACKAGES_POLICY,
                USER_CONTROLLED_DISABLED_PACKAGES);
        policyDefinitions.put(
                DevicePolicyIdentifiers.PERSISTENT_PREFERRED_ACTIVITY_POLICY,
                GENERIC_PERSISTENT_PREFERRED_ACTIVITY);
        policyDefinitions.put(
                DevicePolicyIdentifiers.PACKAGE_UNINSTALL_BLOCKED_POLICY,
                GENERIC_PACKAGE_UNINSTALL_BLOCKED);
        policyDefinitions.put(
                DevicePolicyIdentifiers.APPLICATION_RESTRICTIONS_POLICY,
                GENERIC_APPLICATION_RESTRICTIONS);
        policyDefinitions.put(
                DevicePolicyIdentifiers.RESET_PASSWORD_TOKEN_POLICY, RESET_PASSWORD_TOKEN);
        policyDefinitions.put(
                DevicePolicyIdentifiers.KEYGUARD_DISABLED_FEATURES_POLICY,
                KEYGUARD_DISABLED_FEATURES);
        policyDefinitions.put(
                DevicePolicyIdentifiers.APPLICATION_HIDDEN_POLICY, GENERIC_APPLICATION_HIDDEN);
        policyDefinitions.put(
                DevicePolicyIdentifiers.ACCOUNT_MANAGEMENT_DISABLED_POLICY,
                GENERIC_ACCOUNT_MANAGEMENT_DISABLED);
        policyDefinitions.put(
                DevicePolicyIdentifiers.PERMITTED_INPUT_METHODS_POLICY, PERMITTED_INPUT_METHODS);
        policyDefinitions.put(
                DevicePolicyIdentifiers.SCREEN_CAPTURE_DISABLED_POLICY, SCREEN_CAPTURE_DISABLED);
        policyDefinitions.put(
                DevicePolicyIdentifiers.PERSONAL_APPS_SUSPENDED_POLICY, PERSONAL_APPS_SUSPENDED);
        policyDefinitions.put(
                DevicePolicyIdentifiers.USB_DATA_SIGNALING_POLICY, USB_DATA_SIGNALING);
        policyDefinitions.put(
                DevicePolicyIdentifiers.CONTENT_PROTECTION_POLICY, CONTENT_PROTECTION);
        policyDefinitions.put(DevicePolicyIdentifiers.APP_FUNCTIONS_POLICY, APP_FUNCTIONS);
        // Intentionally not flagged since if the flag is flipped off on a device already
        // having PASSWORD_COMPLEXITY policy in the on-device XML, it will cause the
        // deserialization logic to break due to seeing an unknown tag.
        policyDefinitions.put(
                DevicePolicyIdentifiers.PASSWORD_COMPLEXITY_POLICY, PASSWORD_COMPLEXITY);
        policyDefinitions.put(
                DevicePolicyIdentifiers.PACKAGES_SUSPENDED_POLICY, PACKAGES_SUSPENDED);
        policyDefinitions.put(DevicePolicyIdentifiers.MEMORY_TAGGING_POLICY, MEMORY_TAGGING);
        policyDefinitions.put(
                DevicePolicyIdentifiers.CROSS_PROFILE_WIDGET_PROVIDER_POLICY,
                CROSS_PROFILE_WIDGET_PROVIDER);
        policyDefinitions.put(
                DevicePolicyIdentifiers.COMMON_CRITERIA_MODE_POLICY, COMMON_CRITERIA_MODE);

        // User Restriction Policies
        Map<String, Integer> userRestrictionFlags = new HashMap<>();

        userRestrictionFlags.put(UserManager.DISALLOW_MODIFY_ACCOUNTS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_WIFI, /* flags= */ 0);
        userRestrictionFlags.put(
                UserManager.DISALLOW_CHANGE_WIFI_STATE, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(
                UserManager.DISALLOW_WIFI_TETHERING, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(UserManager.DISALLOW_GRANT_ADMIN, /* flags= */ 0);
        // TODO: set as global only once we get rid of the mapping
        userRestrictionFlags.put(
                UserManager.DISALLOW_SHARING_ADMIN_CONFIGURED_WIFI, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_WIFI_DIRECT, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(
                UserManager.DISALLOW_ADD_WIFI_CONFIG, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_LOCALE, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_INSTALL_APPS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_UNINSTALL_APPS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_SHARE_LOCATION, /* flags= */ 0);
        userRestrictionFlags.put(
                UserManager.DISALLOW_AIRPLANE_MODE, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_BRIGHTNESS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_AMBIENT_DISPLAY, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, /* flags= */ 0);
        userRestrictionFlags.put(
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
                POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_BLUETOOTH, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_BLUETOOTH, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_BLUETOOTH_SHARING, /* flags= */ 0);
        // This effectively always applies globally, but it can be set on the profile
        // parent, check the javadocs on the restriction for more info.
        userRestrictionFlags.put(UserManager.DISALLOW_USB_FILE_TRANSFER, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_CREDENTIALS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_REMOVE_USER, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_REMOVE_MANAGED_PROFILE, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_DEBUGGING_FEATURES, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_VPN, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_LOCATION, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_DATE_TIME, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_TETHERING, /* flags= */ 0);
        // This effectively always applies globally, but it can be set on the profile
        // parent, check the javadocs on the restriction for more info.
        userRestrictionFlags.put(UserManager.DISALLOW_NETWORK_RESET, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_FACTORY_RESET, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_ADD_USER, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_ADD_GUEST, /*flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_ADD_MANAGED_PROFILE, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_ADD_CLONE_PROFILE, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_ADD_PRIVATE_PROFILE, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.ENSURE_VERIFY_APPS, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_CELL_BROADCASTS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_APPS_CONTROL, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_UNMUTE_MICROPHONE, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_ADJUST_VOLUME, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_OUTGOING_CALLS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_SMS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_NETWORK, /* flags= */ 0);
        // TODO: check if its global only
        userRestrictionFlags.put(UserManager.DISALLOW_FUN, /* flags= */ 0);
        // TODO: check if its global only
        userRestrictionFlags.put(UserManager.DISALLOW_CREATE_WINDOWS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE, /* flags= */ 0);
        // TODO: check if its global only
        userRestrictionFlags.put(UserManager.DISALLOW_OUTGOING_BEAM, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_WALLPAPER, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_SET_WALLPAPER, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_SAFE_BOOT, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_RECORD_AUDIO, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_RUN_IN_BACKGROUND, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CAMERA, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_UNMUTE_DEVICE, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_DATA_ROAMING, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_SET_USER_ICON, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_OEM_UNLOCK, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_UNIFIED_PASSWORD, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.ALLOW_PARENT_PROFILE_APP_LINKING, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_AUTOFILL, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONTENT_CAPTURE, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONTENT_SUGGESTIONS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_USER_SWITCH, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_PRINTING, /* flags= */ 0);
        userRestrictionFlags.put(
                UserManager.DISALLOW_CONFIG_PRIVATE_DNS, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(UserManager.DISALLOW_MICROPHONE_TOGGLE, /* flags= */ 0);
        // TODO: According the UserRestrictionsUtils, this is global only, need to confirm.
        userRestrictionFlags.put(UserManager.DISALLOW_CAMERA_TOGGLE, /* flags= */ 0);
        // TODO: check if its global only
        userRestrictionFlags.put(UserManager.DISALLOW_BIOMETRIC, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CONFIG_DEFAULT_APPS, /* flags= */ 0);
        userRestrictionFlags.put(UserManager.DISALLOW_CELLULAR_2G, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(
                UserManager.DISALLOW_NON_TOOL_ACCESSIBILITY_SERVICE,
                POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(
                UserManager.DISALLOW_ULTRA_WIDEBAND_RADIO, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(
                UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO,
                POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(UserManager.DISALLOW_SIM_GLOBALLY, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        userRestrictionFlags.put(UserManager.DISALLOW_ASSIST_CONTENT, /* flags= */ 0);
        userRestrictionFlags.put(
                UserManager.DISALLOW_CHANGE_NEAR_FIELD_COMMUNICATION_RADIO,
                POLICY_FLAG_GLOBAL_ONLY_POLICY);

        if (com.android.net.thread.platform.flags.Flags.threadUserRestrictionEnabled()) {
            userRestrictionFlags.put(
                    UserManager.DISALLOW_THREAD_NETWORK, POLICY_FLAG_GLOBAL_ONLY_POLICY);
        }
        userRestrictionFlags.forEach(
                (restriction, flags) -> {
                    createAndAddUserRestrictionPolicyDefinition(
                            policyDefinitions, restriction, flags);
                });

        return policyDefinitions;
    }

    /**
     * Merges the policies inside {@code right} into the map {@code left}.
     * @throws {@link IllegalStateException} if a naming conflict is encountered.
     */
    private static Map<String, PolicyDefinition<?>> merge(
            Map<String, PolicyDefinition<?>> left, Collection<PolicyDefinition<?>> right) {
        for (PolicyDefinition<?> definition : right) {
            String identifier = definition.getPolicyKey().getIdentifier();

            if (left.containsKey(identifier)) {
                throw new IllegalStateException(
                        "Multiple PolicyDefinition instances found with key " + identifier);
            }

            left.put(identifier, definition);
        }

        return left;
    }

    private static Set<String> loadGenericPolicies() {
        return Set.of(
                DevicePolicyIdentifiers.PERMISSION_GRANT_POLICY,
                DevicePolicyIdentifiers.PERSISTENT_PREFERRED_ACTIVITY_POLICY,
                DevicePolicyIdentifiers.PACKAGE_UNINSTALL_BLOCKED_POLICY,
                DevicePolicyIdentifiers.APPLICATION_RESTRICTIONS_POLICY,
                DevicePolicyIdentifiers.APPLICATION_HIDDEN_POLICY,
                DevicePolicyIdentifiers.ACCOUNT_MANAGEMENT_DISABLED_POLICY);
    }

    /**
     * Adds legacy policies to mLegacyPolicies.
     * @param policyDefinitions - map of all policies. Must be populated with items. Otherwise it
     *                         throws an {@link IllegalStateException}.
     */
    private void populateLegacyPolicies(
            @NonNull Map<String, PolicyDefinition<?>> policyDefinitions) {
        if (policyDefinitions.isEmpty()) {
            throw new IllegalStateException(
                    "addLegacyPolicies must be called after policyDefinitions are filled.");
        }
        populateLegacyPolicy(policyDefinitions,
                DevicePolicyIdentifiers.MANAGED_PROFILE_CALLER_ID_ACCESS_POLICY, null);
        populateLegacyPolicy(policyDefinitions,
                DevicePolicyIdentifiers.MANAGED_PROFILE_CONTACTS_ACCESS_POLICY, null);
        populateLegacyPolicy(policyDefinitions, DevicePolicyIdentifiers.MAX_TIME_TO_LOCK_POLICY,
                null);
        populateLegacyPolicy(policyDefinitions, DevicePolicyIdentifiers.PASSWORD_QUALITY_POLICY,
                null);
        populateLegacyPolicy(policyDefinitions, DevicePolicyIdentifiers.PASSWORD_COMPLEXITY_POLICY,
                () -> Flags.unmanagedModeMigration());
    }

    /**
     * Specifies a policy as a legacy one and adds it to mLegacyPolicies. Ensures that each policy
     * is either legacy or migrated into policy engine.
     * Must be invoked only after policyDefinitions is filled in.
     *
     * @param flag - migration flag for the policy. null if the policy isn't being migrated.
     * @param policyDefinitions - must be populated with existing policy definitions.
     */
    private void populateLegacyPolicy(@NonNull Map<String, PolicyDefinition<?>> policyDefinitions,
            @NonNull String identifier, @Nullable BooleanSupplier flag) {
        // If there's no flag, it means that there's no ongoing migration going on for the
        // legacy policy. In that case, that policy shouldn't exist in the policyDefinitions yet.
        if (flag == null && policyDefinitions.containsKey(identifier)) {
            throw new IllegalStateException("Policy with identifier (" + identifier
                    + ") is already defined as legacy policy. Remove it from policyDefinitions "
                    + "before adding it as a legacy policy.");
        }
        // If there's a migration going on, only add it to the legacy policies if the migration
        // flag is off.
        if (flag == null || !flag.getAsBoolean()) {
            mLegacyPolicies.add(identifier);
        }
    }

    private static void createAndAddUserRestrictionPolicyDefinition(
            Map<String, PolicyDefinition<?>> policyDefinitions,
            String restriction,
            int flags
    ) {
        String identifier = DevicePolicyIdentifiers.getIdentifierForUserRestriction(restriction);
        UserRestrictionPolicyKey key = new UserRestrictionPolicyKey(identifier, restriction);
        flags |= (POLICY_FLAG_USER_RESTRICTION_POLICY | POLICY_FLAG_INHERITABLE);
        PolicyDefinition<Boolean> definition = new PolicyDefinition<>(
                key,
                TRUE_MORE_RESTRICTIVE,
                flags,
                PolicyEnforcerCallbacks::setUserRestriction,
                new BooleanPolicySerializer());
        policyDefinitions.put(key.getIdentifier(), definition);
    }

    @SuppressWarnings("unchecked")
    PolicyDefinition<Boolean> getPolicyDefinitionForUserRestriction(
            @UserManager.UserRestrictionKey String restriction) {
        String key = DevicePolicyIdentifiers.getIdentifierForUserRestriction(restriction);

        if (!mPolicyDefinitions.containsKey(key)) {
            throw new IllegalArgumentException("Unsupported user restriction " + restriction);
        }
        // All user restrictions are of type boolean
        return (PolicyDefinition<Boolean>) mPolicyDefinitions.get(key);
    }

    @Nullable
    PolicyDefinition<?> getPolicyDefinitionForIdentifier(@NonNull String identifier) {
        return mPolicyDefinitions.get(identifier);
    }

    /**
     * Checks if the policy is a legacy policy that's not yet supported by DevicePolicyEngine. If
     * the policy has a ongoing migration to DevicePolicyEngine, this method will return true
     * only when the migration flag is disabled.
     * @param identifier - {@link DevicePolicyIdentifiers} constant of the policy.
     */
    boolean isLegacyPolicy(@NonNull String identifier) {
        return mLegacyPolicies.contains(identifier);
    }

    @Nullable
    <V> PolicyDefinition<V> readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        // TODO: can we avoid casting?
        PolicyKey policyKey = readPolicyKeyFromXml(parser);
        if (policyKey == null) {
            Slogf.wtf(TAG, "Error parsing PolicyDefinition, PolicyKey is null.");
            return null;
        }
        PolicyDefinition<V> genericPolicyDefinition =
                (PolicyDefinition<V>) mPolicyDefinitions.get(policyKey.getIdentifier());
        if (genericPolicyDefinition == null) {
            Slogf.wtf(TAG, "Unknown generic policy key: " + policyKey);
            return null;
        }
        return genericPolicyDefinition.createPolicyDefinition(policyKey);
    }

    @Nullable
    PolicyKey readPolicyKeyFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        PolicyKey policyKey = PolicyKey.readGenericPolicyKeyFromXml(parser);
        if (policyKey == null) {
            Slogf.wtf(TAG, "Error parsing PolicyKey, GenericPolicyKey is null");
            return null;
        }
        PolicyDefinition<?> genericPolicyDefinition =
                mPolicyDefinitions.get(policyKey.getIdentifier());
        if (genericPolicyDefinition == null) {
            Slogf.wtf(TAG, "Error parsing PolicyKey, Unknown generic policy key: " + policyKey);
            return null;
        }
        return genericPolicyDefinition.getPolicyKey().readFromXml(parser);
    }

    <V> boolean isGenericDefinition(PolicyDefinition<V> definition) {
        return mGenericPolicyDefinitions.contains(definition);
    }
}
