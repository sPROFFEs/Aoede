import SwiftUI

struct ServerSetupView: View {
    @Binding var currentServerURL: String?
    var onSaved: (String) -> Void
    var onCancel: (() -> Void)?

    @State private var inputURL: String = ""
    @State private var errorMessage: String?
    @State private var isTesting: Bool = false

    var isFirstRun: Bool {
        onCancel == nil
    }

    var body: some View {
        ZStack {
            Color(red: 0.05, green: 0.05, blue: 0.07)
                .ignoresSafeArea()

            VStack(spacing: 28) {
                Spacer()

                // Logo & Header
                VStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill(
                                LinearGradient(
                                    colors: [
                                        Color(red: 0.22, green: 0.85, blue: 0.55),
                                        Color(red: 0.12, green: 0.60, blue: 0.40)
                                    ],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                            .frame(width: 80, height: 80)
                            .shadow(color: Color(red: 0.22, green: 0.85, blue: 0.55).opacity(0.35), radius: 16)

                        Image(systemName: "music.note")
                            .font(.system(size: 38, weight: .bold))
                            .foregroundColor(.white)
                    }

                    Text("Aoede")
                        .font(.system(size: 32, weight: .bold, design: .rounded))
                        .foregroundColor(.white)

                    Text(isFirstRun ? "Connect to your self-hosted music cloud" : "Update your Aoede server address")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(Color(white: 0.65))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }

                // Input card
                VStack(alignment: .leading, spacing: 14) {
                    Text("SERVER ADDRESS")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(Color(white: 0.5))
                        .tracking(1.0)

                    HStack {
                        Image(systemName: "globe")
                            .foregroundColor(Color(white: 0.45))
                            .font(.system(size: 16))

                        TextField("https://aoede.example.com", text: $inputURL)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .disableAutocorrection(true)
                            .foregroundColor(.white)
                            .font(.system(size: 16, weight: .regular))

                        if !inputURL.isEmpty {
                            Button(action: { inputURL = "" }) {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundColor(Color(white: 0.4))
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .background(Color(white: 0.12))
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(
                                errorMessage != nil ? Color.red.opacity(0.8) : Color(white: 0.2),
                                lineWidth: 1
                            )
                    )

                    if let err = errorMessage {
                        HStack(spacing: 6) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .font(.system(size: 12))
                            Text(err)
                                .font(.system(size: 13, weight: .medium))
                        }
                        .foregroundColor(Color.red.opacity(0.9))
                        .padding(.top, 2)
                    }

                    Text("Tip: Enter full address including https://. Local LAN IPs (e.g. http://192.168.1.50:8000) are also supported.")
                        .font(.system(size: 12))
                        .foregroundColor(Color(white: 0.45))
                        .lineSpacing(2)
                        .padding(.top, 4)
                }
                .padding(22)
                .background(Color(white: 0.08))
                .cornerRadius(18)
                .padding(.horizontal, 24)

                // Actions
                VStack(spacing: 12) {
                    Button(action: validateAndSave) {
                        HStack(spacing: 10) {
                            if isTesting {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .black))
                            }
                            Text(isTesting ? "Connecting…" : "Connect")
                                .font(.system(size: 16, weight: .bold))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                        .background(
                            LinearGradient(
                                colors: [
                                    Color(red: 0.22, green: 0.85, blue: 0.55),
                                    Color(red: 0.16, green: 0.72, blue: 0.46)
                                ],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .foregroundColor(.black)
                        .cornerRadius(14)
                        .shadow(color: Color(red: 0.22, green: 0.85, blue: 0.55).opacity(0.25), radius: 8)
                    }
                    .disabled(isTesting)

                    if let cancel = onCancel {
                        Button(action: cancel) {
                            Text("Cancel")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(Color(white: 0.6))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                    }
                }
                .padding(.horizontal, 24)

                Spacer()
            }
        }
        .onAppear {
            if let current = currentServerURL, !current.isEmpty {
                inputURL = current
            }
        }
    }

    private func validateAndSave() {
        errorMessage = nil
        var trimmed = inputURL.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !trimmed.isEmpty else {
            errorMessage = "Server address cannot be empty."
            return
        }

        if !trimmed.hasPrefix("http://") && !trimmed.hasPrefix("https://") {
            trimmed = "https://" + trimmed
        }

        while trimmed.hasSuffix("/") {
            trimmed.removeLast()
        }

        guard let url = URL(string: trimmed), let host = url.host, !host.isEmpty else {
            errorMessage = "Please enter a valid URL (e.g. https://aoede.example.com)"
            return
        }

        isTesting = true

        // Quick connectivity check to the health/version or portal route
        var req = URLRequest(url: url)
        req.timeoutInterval = 8.0
        req.httpMethod = "GET"

        URLSession.shared.dataTask(with: req) { _, response, error in
            DispatchQueue.main.async {
                self.isTesting = false

                // If reachable (HTTP response received) or even 401/403/200, it's an active server!
                if let http = response as? HTTPURLResponse {
                    if http.statusCode < 500 {
                        self.onSaved(trimmed)
                    } else {
                        self.errorMessage = "Server returned an error (HTTP \(http.statusCode))."
                    }
                } else if let err = error as NSError? {
                    // Allow saving even if offline, but give warning
                    if err.domain == NSURLErrorDomain && (err.code == NSURLErrorCannotConnectToHost || err.code == NSURLErrorTimedOut) {
                        self.errorMessage = "Could not connect to \(host). Check network or server status."
                    } else {
                        // Accept anyway if user confirms or save
                        self.onSaved(trimmed)
                    }
                } else {
                    self.onSaved(trimmed)
                }
            }
        }.resume()
    }
}
