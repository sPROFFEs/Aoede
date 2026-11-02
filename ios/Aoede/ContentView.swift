import SwiftUI

struct ContentView: View {
    @AppStorage("aoede_server_url") private var serverURL: String = ""
    @State private var showingSettingsSheet: Bool = false
    @State private var loadError: String? = nil

    var body: some View {
        ZStack {
            Color(red: 0.05, green: 0.05, blue: 0.07)
                .ignoresSafeArea()

            if serverURL.isEmpty {
                ServerSetupView(
                    currentServerURL: .constant(nil),
                    onSaved: { newURL in
                        serverURL = newURL
                        loadError = nil
                    },
                    onCancel: nil
                )
            } else {
                VStack(spacing: 0) {
                    WebContainerView(
                        serverURL: serverURL,
                        onShowServerSettings: {
                            showingSettingsSheet = true
                        },
                        onError: { error in
                            loadError = error.localizedDescription
                        }
                    )
                    .ignoresSafeArea(.all, edges: .bottom)
                }

                // Error overlay if connection failed completely
                if let error = loadError {
                    ZStack {
                        Color.black.opacity(0.85)
                            .ignoresSafeArea()

                        VStack(spacing: 18) {
                            Image(systemName: "wifi.slash")
                                .font(.system(size: 48))
                                .foregroundColor(Color(red: 0.95, green: 0.40, blue: 0.40))

                            Text("Unable to Connect")
                                .font(.system(size: 22, weight: .bold))
                                .foregroundColor(.white)

                            Text(error)
                                .font(.system(size: 14))
                                .foregroundColor(Color(white: 0.7))
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 32)

                            VStack(spacing: 10) {
                                Button(action: {
                                    loadError = nil
                                }) {
                                    Text("Retry")
                                        .font(.system(size: 15, weight: .bold))
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 12)
                                        .background(Color(red: 0.22, green: 0.85, blue: 0.55))
                                        .foregroundColor(.black)
                                        .cornerRadius(10)
                                }

                                Button(action: {
                                    showingSettingsSheet = true
                                }) {
                                    Text("Change Server Address")
                                        .font(.system(size: 14, weight: .medium))
                                        .foregroundColor(Color(white: 0.7))
                                        .padding(.vertical, 8)
                                }
                            }
                            .frame(maxWidth: 240)
                            .padding(.top, 12)
                        }
                        .padding(28)
                        .background(Color(white: 0.12))
                        .cornerRadius(20)
                        .padding(.horizontal, 28)
                    }
                }
            }
        }
        .sheet(isPresented: $showingSettingsSheet) {
            ServerSetupView(
                currentServerURL: Binding(
                    get: { serverURL },
                    set: { serverURL = $0 }
                ),
                onSaved: { newURL in
                    serverURL = newURL
                    loadError = nil
                    showingSettingsSheet = false
                },
                onCancel: {
                    showingSettingsSheet = false
                }
            )
        }
    }
}
