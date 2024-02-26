package org.bouncycastle.openpgp.operator.jcajce;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.interfaces.RSAKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.interfaces.DHKey;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cryptlib.CryptlibObjectIdentifiers;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParametersHolder;
import org.bouncycastle.bcpg.AEADEncDataPacket;
import org.bouncycastle.bcpg.ECDHPublicBCPGKey;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.bcpg.SymmetricEncIntegrityPacket;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.X25519PublicBCPGKey;
import org.bouncycastle.bcpg.X448PublicBCPGKey;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X448PublicKeyParameters;
import org.bouncycastle.jcajce.spec.UserKeyingMaterialSpec;
import org.bouncycastle.jcajce.util.DefaultJcaJceHelper;
import org.bouncycastle.jcajce.util.NamedJcaJceHelper;
import org.bouncycastle.jcajce.util.ProviderJcaJceHelper;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSessionKey;
import org.bouncycastle.openpgp.operator.PGPDataDecryptor;
import org.bouncycastle.openpgp.operator.PGPPad;
import org.bouncycastle.openpgp.operator.PublicKeyDataDecryptorFactory;
import org.bouncycastle.openpgp.operator.RFC6637Utils;
import org.bouncycastle.util.Arrays;

public class JcePublicKeyDataDecryptorFactoryBuilder
{
    private OperatorHelper helper = new OperatorHelper(new DefaultJcaJceHelper());
    private OperatorHelper contentHelper = new OperatorHelper(new DefaultJcaJceHelper());
    private JceAEADUtil aeadHelper = new JceAEADUtil(contentHelper);
    private JcaPGPKeyConverter keyConverter = new JcaPGPKeyConverter();
    private JcaKeyFingerprintCalculator fingerprintCalculator = new JcaKeyFingerprintCalculator();

    public JcePublicKeyDataDecryptorFactoryBuilder()
    {
    }

    /**
     * Set the provider object to use for creating cryptographic primitives in the resulting factory the builder produces.
     *
     * @param provider provider object for cryptographic primitives.
     * @return the current builder.
     */
    public JcePublicKeyDataDecryptorFactoryBuilder setProvider(Provider provider)
    {
        this.helper = new OperatorHelper(new ProviderJcaJceHelper(provider));
        keyConverter.setProvider(provider);
        this.contentHelper = helper;
        this.aeadHelper = new JceAEADUtil(contentHelper);

        return this;
    }

    /**
     * Set the provider name to use for creating cryptographic primitives in the resulting factory the builder produces.
     *
     * @param providerName the name of the provider to reference for cryptographic primitives.
     * @return the current builder.
     */
    public JcePublicKeyDataDecryptorFactoryBuilder setProvider(String providerName)
    {
        this.helper = new OperatorHelper(new NamedJcaJceHelper(providerName));
        keyConverter.setProvider(providerName);
        this.contentHelper = helper;
        this.aeadHelper = new JceAEADUtil(contentHelper);

        return this;
    }

    public JcePublicKeyDataDecryptorFactoryBuilder setContentProvider(Provider provider)
    {
        this.contentHelper = new OperatorHelper(new ProviderJcaJceHelper(provider));
        this.aeadHelper = new JceAEADUtil(contentHelper);

        return this;
    }

    public JcePublicKeyDataDecryptorFactoryBuilder setContentProvider(String providerName)
    {
        this.contentHelper = new OperatorHelper(new NamedJcaJceHelper(providerName));
        this.aeadHelper = new JceAEADUtil(contentHelper);

        return this;
    }

    private int getExpectedPayloadSize(PrivateKey key)
    {
        if (key instanceof DHKey)
        {
            DHKey k = (DHKey)key;

            return (k.getParams().getP().bitLength() + 7) / 8;
        }
        else if (key instanceof RSAKey)
        {
            RSAKey k = (RSAKey)key;

            return (k.getModulus().bitLength() + 7) / 8;
        }
        else
        {
            return -1;
        }
    }

    public PublicKeyDataDecryptorFactory build(final PrivateKey privKey)
    {
        return new PublicKeyDataDecryptorFactory()
        {
            final int expectedPayLoadSize = getExpectedPayloadSize(privKey);

            @Override
            public byte[] recoverSessionData(int keyAlgorithm, byte[][] secKeyData)
                throws PGPException
            {
                if (keyAlgorithm == PublicKeyAlgorithmTags.ECDH || keyAlgorithm == PublicKeyAlgorithmTags.X25519 || keyAlgorithm == PublicKeyAlgorithmTags.X448)
                {
                    throw new PGPException("ECDH requires use of PGPPrivateKey for decryption");
                }
                return decryptSessionData(keyAlgorithm, privKey, expectedPayLoadSize, secKeyData);
            }

            // OpenPGP v4
            @Override
            public PGPDataDecryptor createDataDecryptor(boolean withIntegrityPacket, int encAlgorithm, byte[] key)
                throws PGPException
            {
                return contentHelper.createDataDecryptor(withIntegrityPacket, encAlgorithm, key);
            }

            // OpenPGP v5
            @Override
            public PGPDataDecryptor createDataDecryptor(AEADEncDataPacket aeadEncDataPacket, PGPSessionKey sessionKey)
                throws PGPException
            {
                return aeadHelper.createOpenPgpV5DataDecryptor(aeadEncDataPacket, sessionKey);
            }

            // OpenPGP v6
            @Override
            public PGPDataDecryptor createDataDecryptor(SymmetricEncIntegrityPacket seipd, PGPSessionKey sessionKey)
                throws PGPException
            {
                return aeadHelper.createOpenPgpV6DataDecryptor(seipd, sessionKey);
            }
        };
    }

    public PublicKeyDataDecryptorFactory build(final PGPPrivateKey privKey)
    {
        return new PublicKeyDataDecryptorFactory()
        {
            @Override
            public byte[] recoverSessionData(int keyAlgorithm, byte[][] secKeyData)
                throws PGPException
            {
                if (keyAlgorithm == PublicKeyAlgorithmTags.ECDH || keyAlgorithm == PublicKeyAlgorithmTags.X25519 || keyAlgorithm == PublicKeyAlgorithmTags.X448)
                {
                    return decryptSessionData(keyConverter, privKey, secKeyData);
                }
                PrivateKey jcePrivKey = keyConverter.getPrivateKey(privKey);
                int expectedPayLoadSize = getExpectedPayloadSize(jcePrivKey);

                return decryptSessionData(keyAlgorithm, jcePrivKey, expectedPayLoadSize, secKeyData);
            }

            // OpenPGP v4
            @Override
            public PGPDataDecryptor createDataDecryptor(boolean withIntegrityPacket, int encAlgorithm, byte[] key)
                throws PGPException
            {
                return contentHelper.createDataDecryptor(withIntegrityPacket, encAlgorithm, key);
            }

            // OpenPGP v5
            @Override
            public PGPDataDecryptor createDataDecryptor(AEADEncDataPacket aeadEncDataPacket, PGPSessionKey sessionKey)
                throws PGPException
            {
                return aeadHelper.createOpenPgpV5DataDecryptor(aeadEncDataPacket, sessionKey);
            }

            // OpenPGP v6
            @Override
            public PGPDataDecryptor createDataDecryptor(SymmetricEncIntegrityPacket seipd, PGPSessionKey sessionKey)
                throws PGPException
            {
                return aeadHelper.createOpenPgpV6DataDecryptor(seipd, sessionKey);
            }
        };
    }

    private byte[] decryptSessionData(JcaPGPKeyConverter converter, PGPPrivateKey privKey, byte[][] secKeyData)
        throws PGPException
    {
        PublicKeyPacket pubKeyData = privKey.getPublicKeyPacket();

        int symmetricKeyAlgorithm = 0;
        byte[] enc = secKeyData[0];
        int pLen;
        byte[] pEnc;
        byte[] keyEnc;
        if (pubKeyData.getKey() instanceof ECDHPublicBCPGKey)
        {
            pLen = ((((enc[0] & 0xff) << 8) + (enc[1] & 0xff)) + 7) / 8;
            if ((2 + pLen + 1) > enc.length)
            {
                throw new PGPException("encoded length out of range");
            }

            pEnc = new byte[pLen];
            System.arraycopy(enc, 2, pEnc, 0, pLen);
            int keyLen = enc[pLen + 2] & 0xff;
            if ((2 + pLen + 1 + keyLen) > enc.length)
            {
                throw new PGPException("encoded length out of range");
            }

            keyEnc = new byte[keyLen];
            System.arraycopy(enc, 2 + pLen + 1, keyEnc, 0, keyLen);
        }
        else if (pubKeyData.getKey() instanceof X25519PublicBCPGKey)
        {
            pLen = X25519PublicBCPGKey.LENGTH;
            pEnc = new byte[pLen];
            System.arraycopy(enc, 0, pEnc, 0, pLen);
            int keyLen = enc[pLen] & 0xff;
            if ((pLen + 1 + keyLen) > enc.length)
            {
                throw new PGPException("encoded length out of range");
            }
//            symmetricKeyAlgorithm = enc[pLen + 1] & 0xff;
            keyEnc = new byte[keyLen - 1];
            System.arraycopy(enc, pLen + 2, keyEnc, 0, keyEnc.length);
        }
        else
        {
            pLen = X448PublicBCPGKey.LENGTH;
            pEnc = new byte[pLen];
            System.arraycopy(enc, 0, pEnc, 0, pLen);
            int keyLen = enc[pLen] & 0xff;
            if ((pLen + 1 + keyLen) > enc.length)
            {
                throw new PGPException("encoded length out of range");
            }
//            symmetricKeyAlgorithm = enc[pLen + 1] & 0xff;
            keyEnc = new byte[keyLen - 1];
            System.arraycopy(enc, pLen + 2, keyEnc, 0, keyEnc.length);
        }

        try
        {
            KeyAgreement agreement;
            PublicKey publicKey;

            ASN1ObjectIdentifier curveID;
            if (pubKeyData.getKey() instanceof ECDHPublicBCPGKey)
            {
                ECDHPublicBCPGKey ecKey = (ECDHPublicBCPGKey)pubKeyData.getKey();
                symmetricKeyAlgorithm = ecKey.getSymmetricKeyAlgorithm();
                curveID = ecKey.getCurveOID();
                // XDH
                if (curveID.equals(CryptlibObjectIdentifiers.curvey25519))
                {
                    agreement = helper.createKeyAgreement(RFC6637Utils.getXDHAlgorithm(pubKeyData));
                    publicKey = getPublicKey(pEnc, EdECObjectIdentifiers.id_X25519, 1,
                        pEnc.length != (1 + X25519PublicKeyParameters.KEY_SIZE) || 0x40 != pEnc[0], "25519");
                }
                else
                {
                    X9ECParametersHolder x9Params = ECNamedCurveTable.getByOIDLazy(ecKey.getCurveOID());
                    ECPoint publicPoint = x9Params.getCurve().decodePoint(pEnc);

                    agreement = helper.createKeyAgreement(RFC6637Utils.getAgreementAlgorithm(pubKeyData));

                    publicKey = converter.getPublicKey(new PGPPublicKey(new PublicKeyPacket(PublicKeyAlgorithmTags.ECDH, new Date(),
                        new ECDHPublicBCPGKey(ecKey.getCurveOID(), publicPoint, ecKey.getHashAlgorithm(), ecKey.getSymmetricKeyAlgorithm())), fingerprintCalculator));
                }
                byte[] userKeyingMaterial = RFC6637Utils.createUserKeyingMaterial(pubKeyData, fingerprintCalculator);

                PrivateKey privateKey = converter.getPrivateKey(privKey);

                agreement.init(privateKey, new UserKeyingMaterialSpec(userKeyingMaterial));

                agreement.doPhase(publicKey, true);

                Key key = agreement.generateSecret(RFC6637Utils.getKeyEncryptionOID(symmetricKeyAlgorithm).getId());

                Cipher c = helper.createKeyWrapper(symmetricKeyAlgorithm);

                c.init(Cipher.UNWRAP_MODE, key);

                Key paddedSessionKey = c.unwrap(keyEnc, "Session", Cipher.SECRET_KEY);

                return PGPPad.unpadSessionData(paddedSessionKey.getEncoded());
            }
            else if (pubKeyData.getKey() instanceof X25519PublicBCPGKey)
            {
                agreement = helper.createKeyAgreement(RFC6637Utils.getXDHAlgorithm(pubKeyData));
                publicKey = getPublicKey(pEnc, EdECObjectIdentifiers.id_X25519, 0, pEnc.length != (X25519PublicKeyParameters.KEY_SIZE), "25519");
                symmetricKeyAlgorithm = SymmetricKeyAlgorithmTags.AES_128;
            }
            else
            {
                agreement = helper.createKeyAgreement(RFC6637Utils.getXDHAlgorithm(pubKeyData));
                publicKey = getPublicKey(pEnc, EdECObjectIdentifiers.id_X448, 0, pEnc.length != X448PublicKeyParameters.KEY_SIZE, "448");
                symmetricKeyAlgorithm = SymmetricKeyAlgorithmTags.AES_256;
            }

            byte[] userKeyingMaterial = RFC6637Utils.createUserKeyingMaterial(pubKeyData, fingerprintCalculator);

            PrivateKey privateKey = converter.getPrivateKey(privKey);

            agreement.init(privateKey, new UserKeyingMaterialSpec(userKeyingMaterial));

            agreement.doPhase(publicKey, true);

            Key key = agreement.generateSecret(RFC6637Utils.getKeyEncryptionOID(symmetricKeyAlgorithm).getId());

            Cipher c = helper.createKeyWrapper(symmetricKeyAlgorithm);

            c.init(Cipher.UNWRAP_MODE, key);

            Key paddedSessionKey = c.unwrap(keyEnc, "Session", Cipher.SECRET_KEY);
            symmetricKeyAlgorithm = enc[pLen + 1] & 0xff;
            return Arrays.concatenate(new byte[]{(byte)symmetricKeyAlgorithm}, paddedSessionKey.getEncoded());
        }
        catch (InvalidKeyException e)
        {
            throw new PGPException("error setting asymmetric cipher", e);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new PGPException("error setting asymmetric cipher", e);
        }
        catch (InvalidAlgorithmParameterException e)
        {
            throw new PGPException("error setting asymmetric cipher", e);
        }
        catch (GeneralSecurityException e)
        {
            throw new PGPException("error setting asymmetric cipher", e);
        }
        catch (IOException e)
        {
            throw new PGPException("error setting asymmetric cipher", e);
        }
    }

    private PublicKey getPublicKey(byte[] pEnc, ASN1ObjectIdentifier algprithmIdentifier, int pEncOff,
                                   boolean condition, String curve)
        throws PGPException, GeneralSecurityException, IOException
    {
        KeyFactory keyFact = helper.createKeyFactory("XDH");

        if (condition)
        {
            throw new IllegalArgumentException("Invalid Curve" + curve + " public key");
        }

        return keyFact.generatePublic(
            new X509EncodedKeySpec(
                new SubjectPublicKeyInfo(new AlgorithmIdentifier(algprithmIdentifier),
                    Arrays.copyOfRange(pEnc, pEncOff, pEnc.length)).getEncoded()));
    }

    private void updateWithMPI(Cipher c, int expectedPayloadSize, byte[] encMPI)
    {
        if (expectedPayloadSize > 0)
        {
            if (encMPI.length - 2 > expectedPayloadSize)  // leading Zero? Shouldn't happen but...
            {
                c.update(encMPI, 3, encMPI.length - 3);
            }
            else
            {
                if (expectedPayloadSize > (encMPI.length - 2))
                {
                    c.update(new byte[expectedPayloadSize - (encMPI.length - 2)]);
                }
                c.update(encMPI, 2, encMPI.length - 2);
            }
        }
        else
        {
            c.update(encMPI, 2, encMPI.length - 2);
        }
    }

    private byte[] decryptSessionData(int keyAlgorithm, PrivateKey privKey, int expectedPayloadSize, byte[][] secKeyData)
        throws PGPException
    {
        Cipher c1 = helper.createPublicKeyCipher(keyAlgorithm);

        try
        {
            c1.init(Cipher.DECRYPT_MODE, privKey);
        }
        catch (InvalidKeyException e)
        {
            throw new PGPException("error setting asymmetric cipher", e);
        }

        if (keyAlgorithm == PGPPublicKey.RSA_ENCRYPT
            || keyAlgorithm == PGPPublicKey.RSA_GENERAL)
        {
            updateWithMPI(c1, expectedPayloadSize, secKeyData[0]);
        }
        else
        {
            // Elgamal Encryption
            updateWithMPI(c1, expectedPayloadSize, secKeyData[0]);
            updateWithMPI(c1, expectedPayloadSize, secKeyData[1]);
        }

        try
        {
            return c1.doFinal();
        }
        catch (Exception e)
        {
            throw new PGPException("exception decrypting session data", e);
        }
    }
}
