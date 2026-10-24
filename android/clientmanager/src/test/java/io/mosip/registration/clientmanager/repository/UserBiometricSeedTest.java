package io.mosip.registration.clientmanager.repository;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.mosip.registration.clientmanager.dao.UserBiometricDao;
import io.mosip.registration.clientmanager.dao.UserDetailDao;
import io.mosip.registration.clientmanager.entity.UserBiometric;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R2 debug seed (B-09 review): a seed never touches a really onboarded face template. */
public class UserBiometricSeedTest {

    private static final String USER = "110011";
    private static final byte[] ISO = {4, 5, 6};

    private final UserBiometricDao dao = mock(UserBiometricDao.class);
    private final UserBiometricRepository repository = new UserBiometricRepository(dao, mock(UserDetailDao.class));

    @Test
    public void tcAnd10_seed_isRefusedWhenTheUserHasAnOnboardedFace() {
        when(dao.findByUsrId(USER)).thenReturn(Collections.singletonList(row("Face", "face")));

        assertFalse(repository.replaceSeedFaceTemplate(USER, ISO, 90));

        verify(dao, never()).deleteSeedFaceByUsrId(any());
        verify(dao, never()).insertAllUserBiometrics(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void tcAnd10_seed_replacesOnlyEarlierSeedRows() {
        when(dao.findByUsrId(USER)).thenReturn(Arrays.asList(row("Face", "face-seed"), row("Iris", "leftEye")));
        ArgumentCaptor<List<UserBiometric>> inserted = ArgumentCaptor.forClass(List.class);

        assertTrue(repository.replaceSeedFaceTemplate(USER, ISO, 90));

        verify(dao).deleteSeedFaceByUsrId(USER);
        verify(dao).insertAllUserBiometrics(inserted.capture());
        UserBiometric seed = inserted.getValue().get(0);
        assertEquals(UserBiometricRepository.SEED_FACE_ATTRIBUTE, seed.getBioAttributeCode());
        assertEquals("Face", seed.getBioTypeCode());
        assertArrayEquals(ISO, seed.getBioTemplate());
    }

    private static UserBiometric row(String type, String attribute) {
        UserBiometric row = new UserBiometric();
        row.setUsrId(USER);
        row.setBioTypeCode(type);
        row.setBioAttributeCode(attribute);
        row.setIsDeleted(false);
        return row;
    }
}
