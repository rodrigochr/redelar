package com.piggyback.hrvmon;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

/** Canais de comunicação com o adaptador: Bluetooth clássico (SPP) ou Wi-Fi (TCP). */
final class Links {
    interface Link extends Closeable {
        InputStream in() throws IOException;

        OutputStream out() throws IOException;
    }

    interface Factory {
        Link open() throws IOException;

        String describe();
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    static Factory bluetooth(final BluetoothDevice dev, final String name) {
        return new Factory() {
            @Override
            public Link open() throws IOException {
                IOException last = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    BluetoothSocket s = null;
                    try {
                        if (attempt == 0) {
                            s = dev.createRfcommSocketToServiceRecord(SPP);
                        } else if (attempt == 1) {
                            s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                        } else {
                            // Alguns clones só aceitam o canal 1 direto.
                            Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                            s = (BluetoothSocket) m.invoke(dev, 1);
                        }
                        s.connect();
                        return wrap(s);
                    } catch (IOException e) {
                        last = e;
                        closeQuietly(s);
                    } catch (ReflectiveOperationException e) {
                        closeQuietly(s);
                    }
                }
                throw new IOException("não conectou ao " + name
                        + (last != null ? " (" + last.getMessage() + ")" : ""));
            }

            @Override
            public String describe() {
                return name;
            }
        };
    }

    static Factory wifi(final Context ctx, final String host, final int port) {
        return new Factory() {
            @Override
            public Link open() throws IOException {
                // A rede do ELM327 não tem internet; sem isso o Android manda o tráfego pelos dados móveis.
                Socket s = null;
                ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
                if (cm != null) {
                    for (Network n : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            s = n.getSocketFactory().createSocket();
                            break;
                        }
                    }
                }
                if (s == null) s = new Socket();
                try {
                    s.connect(new InetSocketAddress(host, port), 5000);
                    s.setTcpNoDelay(true);
                } catch (IOException e) {
                    closeQuietly(s);
                    throw new IOException("não conectou ao ELM327 Wi-Fi em " + host + ":" + port);
                }
                final Socket sock = s;
                return new Link() {
                    @Override
                    public InputStream in() throws IOException {
                        return sock.getInputStream();
                    }

                    @Override
                    public OutputStream out() throws IOException {
                        return sock.getOutputStream();
                    }

                    @Override
                    public void close() throws IOException {
                        sock.close();
                    }
                };
            }

            @Override
            public String describe() {
                return "ELM327 Wi-Fi";
            }
        };
    }

    private static Link wrap(final BluetoothSocket s) {
        return new Link() {
            @Override
            public InputStream in() throws IOException {
                return s.getInputStream();
            }

            @Override
            public OutputStream out() throws IOException {
                return s.getOutputStream();
            }

            @Override
            public void close() throws IOException {
                s.close();
            }
        };
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private Links() {}
}
