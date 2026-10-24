package io.mosip.registration.clientmanager.service;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mosip.kernel.biometrics.constant.BiometricType;
import io.mosip.kernel.biometrics.constant.Match;
import io.mosip.kernel.biometrics.constant.ProcessedLevelType;
import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.biometrics.model.Decision;
import io.mosip.kernel.biometrics.model.MatchDecision;
import io.mosip.kernel.biometrics.model.Response;
import io.mosip.kernel.biometrics.spi.IBioApiV2;
import io.mosip.registration.clientmanager.constant.Modality;
import io.mosip.registration.clientmanager.dto.registration.BiometricsDto;
import io.mosip.registration.clientmanager.entity.UserBiometric;
import io.mosip.registration.clientmanager.repository.UserBiometricRepository;
import io.mosip.registration.clientmanager.util.BioSdkProviderFactory;
import io.mosip.registration.clientmanager.util.MatchUtil;
import io.mosip.registration.keymanager.util.CryptoUtil;

/**
 * Operator face authentication (B-09, API.md §15): a 1:1 match through the configured BioSDK of a live, freshly
 * captured face against all of the user's onboarding FACE templates. Fails closed: no template, no face match
 * provider or an SDK failure is never a match.
 */
@Singleton
public class FaceAuthService {

    /** How a face authentication ended. */
    public enum Outcome {
        /** The face matches the user's templates. */
        MATCHED,
        /** The face does not match. */
        NOT_MATCHED,
        /** The user has no face template: an enrolment problem, never "try again". */
        NO_TEMPLATE,
        /** No face match provider is configured, or it failed. */
        NO_PROVIDER,
        /** No captured face to match. */
        NO_CAPTURE
    }

    private static final String TAG = FaceAuthService.class.getSimpleName();

    private final UserBiometricRepository userBiometricRepository;
    private final BioSdkProviderFactory bioSdkProviderFactory;

    @Inject
    public FaceAuthService(UserBiometricRepository userBiometricRepository,
                           BioSdkProviderFactory bioSdkProviderFactory) {
        this.userBiometricRepository = userBiometricRepository;
        this.bioSdkProviderFactory = bioSdkProviderFactory;
    }

    /** @return whether a face match provider is configured, so face authentication can be offered */
    public boolean isAvailable() {
        return bioSdkProviderFactory.getProviderForMatch(Modality.FACE) != null;
    }

    /**
     * @param userId  the operator or supervisor
     * @param capture the face just captured through the SBI, after a liveness pass
     * @return how the match ended
     */
    public Outcome authenticate(String userId, List<BiometricsDto> capture) {
        if (capture == null || capture.isEmpty()) {
            return Outcome.NO_CAPTURE;
        }
        List<UserBiometric> templates = userBiometricRepository.findFaceTemplates(userId);
        if (templates == null || templates.isEmpty()) {
            return Outcome.NO_TEMPLATE;
        }
        IBioApiV2 sdk = bioSdkProviderFactory.getProviderForMatch(Modality.FACE);
        if (sdk == null) {
            return Outcome.NO_PROVIDER;
        }
        try {
            Response<MatchDecision[]> response = sdk.match(probe(capture), new BiometricRecord[]{gallery(templates)},
                    Collections.singletonList(BiometricType.FACE), new HashMap<>());
            Decision decision = response == null || response.getResponse() == null
                    || response.getResponse().length == 0 ? null
                    : response.getResponse()[0].getDecisions().get(BiometricType.FACE);
            return decision != null && decision.getMatch() == Match.MATCHED ? Outcome.MATCHED : Outcome.NOT_MATCHED;
        } catch (RuntimeException e) {
            Log.e(TAG, "Face match failed", e);
            return Outcome.NO_PROVIDER;
        }
    }

    /**
     * Debug builds only (B-21 seed, R2): saves the captured face as the user's face template, for face
     * authentication without online onboarding.
     *
     * @param userId  the operator or supervisor
     * @param capture the face just captured through the SBI
     * @return false when there is no face to save
     */
    public boolean seedTemplate(String userId, List<BiometricsDto> capture) {
        if (capture == null || capture.isEmpty()) {
            return false;
        }
        BiometricsDto face = capture.get(0);
        userBiometricRepository.replaceFaceTemplate(userId, CryptoUtil.base64decoder.decode(face.getBioValue()),
                (int) face.getQualityScore());
        return true;
    }

    private static BiometricRecord probe(List<BiometricsDto> capture) {
        BiometricRecord record = new BiometricRecord();
        for (BiometricsDto face : capture) {
            record.getSegments().add(MatchUtil.buildBir(face.getBioSubType(), (long) face.getQualityScore(),
                    CryptoUtil.base64decoder.decode(face.getBioValue()), BiometricType.FACE, ProcessedLevelType.RAW,
                    false));
        }
        return record;
    }

    /** All of the user's templates as one gallery record, so any of them can match. */
    private static BiometricRecord gallery(List<UserBiometric> templates) {
        List<BIR> segments = new ArrayList<>();
        for (UserBiometric template : templates) {
            Integer quality = template.getQualityScore();
            segments.add(MatchUtil.buildBir(template.getBioAttributeCode(), quality == null ? 0 : quality,
                    template.getBioTemplate(), BiometricType.FACE, ProcessedLevelType.PROCESSED, true));
        }
        BiometricRecord record = new BiometricRecord();
        record.getSegments().addAll(segments);
        return record;
    }
}
