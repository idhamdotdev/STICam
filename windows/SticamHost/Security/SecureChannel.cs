using System.Buffers.Binary;
using System.Security;
using System.Security.Cryptography;
using System.Text;

namespace SticamHost.Security;

public readonly record struct SecurePacket(byte Type, byte[] Payload);

internal static class SecureProtocolCrypto
{
    internal static readonly byte[] Magic = Encoding.ASCII.GetBytes("STICAM2\n");
    internal static readonly byte[] KdfSalt = Encoding.UTF8.GetBytes("STICam secure transport v2");
    internal static readonly byte[] PacketAad = Encoding.ASCII.GetBytes("STICAM2-PACKET");
    internal static readonly byte[] ClientLabel = Encoding.ASCII.GetBytes("client");
    internal static readonly byte[] ServerLabel = Encoding.ASCII.GetBytes("server");
    internal static readonly byte[] SessionLabel = Encoding.ASCII.GetBytes("session");
    internal static readonly byte[] ClientFinishLabel = Encoding.ASCII.GetBytes("client-finish");
    internal static readonly byte[] ClientToServerLabel = Encoding.ASCII.GetBytes("client-to-server");
    internal static readonly byte[] ServerToClientLabel = Encoding.ASCII.GetBytes("server-to-client");
    internal const int Pbkdf2Iterations = 120_000;

    internal static byte[] DeriveBaseKey(string pairingKey) => Rfc2898DeriveBytes.Pbkdf2(
        pairingKey,
        KdfSalt,
        Pbkdf2Iterations,
        HashAlgorithmName.SHA256,
        32);

    internal static byte[] Hmac(byte[] key, params byte[][] parts)
    {
        using var hmac = IncrementalHash.CreateHMAC(HashAlgorithmName.SHA256, key);
        foreach (byte[] part in parts) hmac.AppendData(part);
        return hmac.GetHashAndReset();
    }

    internal static byte[] SequenceBytes(long sequence)
    {
        byte[] value = new byte[sizeof(long)];
        BinaryPrimitives.WriteInt64BigEndian(value, sequence);
        return value;
    }

    internal static byte[] Nonce(long sequence)
    {
        byte[] nonce = new byte[12];
        BinaryPrimitives.WriteInt64BigEndian(nonce.AsSpan(4), sequence);
        return nonce;
    }

    internal static byte[] Aad(long sequence)
    {
        byte[] aad = new byte[PacketAad.Length + sizeof(long)];
        PacketAad.CopyTo(aad, 0);
        BinaryPrimitives.WriteInt64BigEndian(aad.AsSpan(PacketAad.Length), sequence);
        return aad;
    }
}

/// <summary>
/// Authenticated version 2 transport for STICam typed packets. Fresh
/// directional keys are derived per connection and records are protected by
/// AES-256-GCM with strictly ordered authenticated sequence counters.
/// </summary>
public sealed class SecureChannel : IAsyncDisposable, IDisposable
{
    private const int RandomSize = 16;
    private const int ProofSize = 32;
    private const int TagSize = 16;
    private const int MaxEncryptedPacket = 20 * 1024 * 1024;
    private const int MaxPayload = MaxEncryptedPacket - TagSize - 5;
    private static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(10);

    private readonly System.IO.Stream _stream;
    private readonly byte[] _sendKey;
    private readonly byte[] _receiveKey;
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly SemaphoreSlim _receiveLock = new(1, 1);
    private long _sendSequence;
    private long _receiveSequence;
    private int _disposed;

    private SecureChannel(System.IO.Stream stream, byte[] sendKey, byte[] receiveKey)
    {
        _stream = stream;
        _sendKey = sendKey;
        _receiveKey = receiveKey;
    }

    public static async Task<SecureChannel> AcceptAsync(
        System.IO.Stream stream,
        string pairingKey,
        CancellationToken cancellationToken)
    {
        string normalizedKey = pairingKey.Trim().ToUpperInvariant();
        if (normalizedKey.Length != 32 || normalizedKey.Any(c => !IsAsciiHex(c)))
            throw new SecurityException("Pairing key must contain exactly 32 hexadecimal characters.");

        using var handshakeCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        handshakeCts.CancelAfter(HandshakeTimeout);
        CancellationToken handshakeToken = handshakeCts.Token;

        byte[] baseKey = SecureProtocolCrypto.DeriveBaseKey(normalizedKey);
        byte[]? sessionKey = null;
        try
        {
            byte[] hello = new byte[SecureProtocolCrypto.Magic.Length + RandomSize + ProofSize];
            await ReadExactlyAsync(stream, hello, handshakeToken).ConfigureAwait(false);
            if (!CryptographicOperations.FixedTimeEquals(
                    hello.AsSpan(0, SecureProtocolCrypto.Magic.Length),
                    SecureProtocolCrypto.Magic))
                throw new SecurityException("Unsupported or unauthenticated STICam protocol.");

            byte[] clientRandom = hello.AsSpan(SecureProtocolCrypto.Magic.Length, RandomSize).ToArray();
            byte[] clientProof = hello.AsSpan(SecureProtocolCrypto.Magic.Length + RandomSize, ProofSize).ToArray();
            byte[] expectedClientProof = SecureProtocolCrypto.Hmac(
                baseKey,
                SecureProtocolCrypto.ClientLabel,
                clientRandom);
            if (!CryptographicOperations.FixedTimeEquals(clientProof, expectedClientProof))
                throw new SecurityException("Android pairing verification failed.");

            byte[] serverRandom = RandomNumberGenerator.GetBytes(RandomSize);
            byte[] serverProof = SecureProtocolCrypto.Hmac(
                baseKey,
                SecureProtocolCrypto.ServerLabel,
                clientRandom,
                serverRandom);
            await stream.WriteAsync(serverRandom, handshakeToken).ConfigureAwait(false);
            await stream.WriteAsync(serverProof, handshakeToken).ConfigureAwait(false);
            await stream.FlushAsync(handshakeToken).ConfigureAwait(false);

            sessionKey = SecureProtocolCrypto.Hmac(
                baseKey,
                SecureProtocolCrypto.SessionLabel,
                clientRandom,
                serverRandom);
            byte[] clientFinish = new byte[ProofSize];
            await ReadExactlyAsync(stream, clientFinish, handshakeToken).ConfigureAwait(false);
            byte[] expectedClientFinish = SecureProtocolCrypto.Hmac(
                sessionKey,
                SecureProtocolCrypto.ClientFinishLabel,
                SecureProtocolCrypto.Magic,
                clientRandom,
                serverRandom,
                clientProof,
                serverProof);
            if (!CryptographicOperations.FixedTimeEquals(clientFinish, expectedClientFinish))
                throw new SecurityException("Android transcript verification failed.");
            byte[] receiveKey = SecureProtocolCrypto.Hmac(
                sessionKey,
                SecureProtocolCrypto.ClientToServerLabel);
            byte[] sendKey = SecureProtocolCrypto.Hmac(
                sessionKey,
                SecureProtocolCrypto.ServerToClientLabel);
            return new SecureChannel(stream, sendKey, receiveKey);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(baseKey);
            if (sessionKey != null) CryptographicOperations.ZeroMemory(sessionKey);
        }
    }

    public async Task SendAsync(byte type, ReadOnlyMemory<byte> payload, CancellationToken cancellationToken)
    {
        if (payload.Length > MaxPayload) throw new InvalidDataException("Packet is too large.");
        ThrowIfDisposed();
        await _sendLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ThrowIfDisposed();
            if (_sendSequence == long.MaxValue) throw new SecurityException("Send sequence exhausted.");
            long sequence = _sendSequence++;
            byte[] plain = new byte[5 + payload.Length];
            try
            {
                plain[0] = type;
                BinaryPrimitives.WriteInt32BigEndian(plain.AsSpan(1, 4), payload.Length);
                payload.CopyTo(plain.AsMemory(5));

                byte[] encrypted = new byte[plain.Length];
                byte[] tag = new byte[TagSize];
                using (var aes = new AesGcm(_sendKey, TagSize))
                {
                    aes.Encrypt(
                        SecureProtocolCrypto.Nonce(sequence),
                        plain,
                        encrypted,
                        tag,
                        SecureProtocolCrypto.Aad(sequence));
                }

                byte[] header = new byte[4];
                BinaryPrimitives.WriteInt32BigEndian(header, encrypted.Length + tag.Length);
                await _stream.WriteAsync(header, cancellationToken).ConfigureAwait(false);
                await _stream.WriteAsync(SecureProtocolCrypto.SequenceBytes(sequence), cancellationToken).ConfigureAwait(false);
                await _stream.WriteAsync(encrypted, cancellationToken).ConfigureAwait(false);
                await _stream.WriteAsync(tag, cancellationToken).ConfigureAwait(false);
                await _stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(plain);
            }
        }
        finally
        {
            _sendLock.Release();
        }
    }

    public async Task<SecurePacket> ReceiveAsync(CancellationToken cancellationToken)
    {
        ThrowIfDisposed();
        await _receiveLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ThrowIfDisposed();
            byte[] header = new byte[4];
            await ReadExactlyAsync(_stream, header, cancellationToken).ConfigureAwait(false);
            int encryptedLength = BinaryPrimitives.ReadInt32BigEndian(header);
            if (encryptedLength is < TagSize + 5 or > MaxEncryptedPacket)
                throw new SecurityException($"Invalid encrypted packet length: {encryptedLength}");

            byte[] sequenceBytes = new byte[sizeof(long)];
            await ReadExactlyAsync(_stream, sequenceBytes, cancellationToken).ConfigureAwait(false);
            long sequence = BinaryPrimitives.ReadInt64BigEndian(sequenceBytes);
            if (sequence < 0 || sequence != _receiveSequence || _receiveSequence == long.MaxValue)
                throw new SecurityException("Unexpected secure record sequence.");
            _receiveSequence++;

            byte[] combined = new byte[encryptedLength];
            await ReadExactlyAsync(_stream, combined, cancellationToken).ConfigureAwait(false);
            int cipherLength = encryptedLength - TagSize;
            byte[] plain = new byte[cipherLength];
            try
            {
                using (var aes = new AesGcm(_receiveKey, TagSize))
                {
                    aes.Decrypt(
                        SecureProtocolCrypto.Nonce(sequence),
                        combined.AsSpan(0, cipherLength),
                        combined.AsSpan(cipherLength, TagSize),
                        plain,
                        SecureProtocolCrypto.Aad(sequence));
                }

                if (plain.Length < 5) throw new SecurityException("Invalid secure packet.");
                int payloadLength = BinaryPrimitives.ReadInt32BigEndian(plain.AsSpan(1, 4));
                if (payloadLength < 0 || payloadLength != plain.Length - 5)
                    throw new SecurityException("Invalid secure payload length.");
                return new SecurePacket(plain[0], plain.AsSpan(5).ToArray());
            }
            finally
            {
                CryptographicOperations.ZeroMemory(plain);
            }
        }
        finally
        {
            _receiveLock.Release();
        }
    }

    private static bool IsAsciiHex(char value) =>
        value is >= '0' and <= '9' or >= 'A' and <= 'F';

    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(
        Volatile.Read(ref _disposed) != 0,
        this);

    private static async Task ReadExactlyAsync(
        System.IO.Stream stream,
        Memory<byte> buffer,
        CancellationToken cancellationToken)
    {
        int offset = 0;
        while (offset < buffer.Length)
        {
            int read = await stream.ReadAsync(buffer[offset..], cancellationToken).ConfigureAwait(false);
            if (read == 0) throw new EndOfStreamException();
            offset += read;
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        _stream.Dispose();
        _sendLock.Wait();
        _receiveLock.Wait();
        try
        {
            CryptographicOperations.ZeroMemory(_sendKey);
            CryptographicOperations.ZeroMemory(_receiveKey);
        }
        finally
        {
            _receiveLock.Release();
            _sendLock.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        await _stream.DisposeAsync().ConfigureAwait(false);
        await _sendLock.WaitAsync().ConfigureAwait(false);
        await _receiveLock.WaitAsync().ConfigureAwait(false);
        try
        {
            CryptographicOperations.ZeroMemory(_sendKey);
            CryptographicOperations.ZeroMemory(_receiveKey);
        }
        finally
        {
            _receiveLock.Release();
            _sendLock.Release();
        }
    }
}
