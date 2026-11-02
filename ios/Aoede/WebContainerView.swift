import SwiftUI
import WebKit

struct WebContainerView: UIViewRepresentable {
    let serverURL: String
    var onShowServerSettings: () -> Void
    var onError: ((Error) -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.allowsAirPlayForMediaPlayback = true
        configuration.suppressesIncrementalRendering = false

        // User script: flag iOS environment & inject client identification
        let clientScriptSource = """
        window.AOEDE_IOS = true;
        window.AOEDE_CLIENT = { platform: "ios", version: "1.0.0" };
        """
        let userScript = WKUserScript(
            source: clientScriptSource,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        )
        configuration.userContentController.addUserScript(userScript)

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = UIColor(red: 0.05, green: 0.05, blue: 0.07, alpha: 1.0)
        webView.scrollView.backgroundColor = UIColor(red: 0.05, green: 0.05, blue: 0.07, alpha: 1.0)
        webView.allowsBackForwardNavigationGestures = true

        // Pull to refresh
        let refreshControl = UIRefreshControl()
        refreshControl.tintColor = UIColor(red: 0.22, green: 0.85, blue: 0.55, alpha: 1.0)
        refreshControl.addTarget(
            context.coordinator,
            action: #selector(Coordinator.handleRefresh(_:)),
            for: .valueChanged
        )
        webView.scrollView.refreshControl = refreshControl

        context.coordinator.webView = webView

        // Wire AudioSession remote command callbacks to webview JS
        AudioSessionManager.shared.onRemoteCommand = { [weak webView] command in
            DispatchQueue.main.async {
                let js: String
                switch command {
                case "play":
                    js = "if (window.audioElement) { window.audioElement.play(); } else { const a = document.querySelector('audio'); if (a) a.play(); }"
                case "pause":
                    js = "if (window.audioElement) { window.audioElement.pause(); } else { const a = document.querySelector('audio'); if (a) a.pause(); }"
                case "toggle":
                    js = "const a = document.querySelector('audio'); if (a) { if (a.paused) a.play(); else a.pause(); }"
                case "nexttrack":
                    js = "const nextBtn = document.querySelector('.player-btn-next, [data-action=\"next\"], #btn-next'); if (nextBtn) nextBtn.click();"
                case "previoustrack":
                    js = "const prevBtn = document.querySelector('.player-btn-prev, [data-action=\"prev\"], #btn-prev'); if (prevBtn) prevBtn.click();"
                default:
                    js = ""
                }
                if !js.isEmpty {
                    webView?.evaluateJavaScript(js, completionHandler: nil)
                }
            }
        }

        // Initial load
        if let url = URL(string: serverURL) {
            let request = URLRequest(url: url)
            webView.load(request)
        }

        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        // If serverURL changed externally, reload
        if let current = uiView.url?.absoluteString, !current.hasPrefix(serverURL) {
            if let next = URL(string: serverURL) {
                uiView.load(URLRequest(url: next))
            }
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var parent: WebContainerView
        weak var webView: WKWebView?

        init(_ parent: WebContainerView) {
            self.parent = parent
        }

        @objc func handleRefresh(_ sender: UIRefreshControl) {
            webView?.reload()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                sender.endRefreshing()
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            let nsErr = error as NSError
            if nsErr.code == NSURLErrorCancelled { return }
            parent.onError?(error)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            let nsErr = error as NSError
            if nsErr.code == NSURLErrorCancelled { return }
            parent.onError?(error)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }

            // Custom scheme: aoede://settings opens native server settings
            if url.scheme == "aoede" && url.host == "settings" {
                parent.onShowServerSettings()
                decisionHandler(.cancel)
                return
            }

            // Open target=_blank or external third-party links (youtube, spotify, etc.) in system browser
            if navigationAction.targetFrame == nil {
                UIApplication.shared.open(url)
                decisionHandler(.cancel)
                return
            }

            decisionHandler(.allow)
        }
    }
}
