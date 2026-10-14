import socket, struct, sys
def cmd(c, host='127.0.0.1', port=25575, pw='foxphys-local-test'):
    s = socket.create_connection((host, port), timeout=20)
    def send(t, i, body):
        b = body.encode()
        s.sendall(struct.pack('<iii', len(b)+10, i, t) + b + b'\x00\x00')
    def recv():
        n = struct.unpack('<i', s.recv(4))[0]
        data = b''
        while len(data) < n: data += s.recv(n-len(data))
        i, t = struct.unpack('<ii', data[:8])
        return i, t, data[8:-2].decode(errors='replace')
    send(3, 1, pw); recv()
    send(2, 2, c)
    out = recv()[2]
    s.close(); return out
if __name__ == '__main__':
    for line in sys.argv[1:]:
        print('>', line); print(cmd(line))
