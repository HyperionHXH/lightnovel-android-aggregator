import SwiftUI
import WebKit
import UIKit

struct ReaderViewport: Equatable {
    var paragraph: Int = 0
    var percent: Int = 0
    var page: Int = 0
    var pageCount: Int = 1
}
struct ReaderWebCommand: Equatable {
    enum Action: Equatable { case move(Int), seek(Double), capture }
    var id = UUID()
    var action: Action
}

/// Own JavaScript runs in WebKit's isolated client world; chapter HTML never runs script.
struct ReaderWebView: UIViewControllerRepresentable {
    var documentID: UUID
    var html: String
    var settings: ReaderSettings
    var restoreParagraph: Int
    var restorePercent: Int
    var restoreAtEnd: Bool
    var command: ReaderWebCommand?
    var onSettled: (ReaderViewport) -> Void
    var onControls: () -> Void
    var onBoundary: (Int) -> Void
    var onImage: (URL, Bool) -> Void
    var onError: (String) -> Void

    func makeUIViewController(context: Context) -> ReaderWebController {
        let controller = ReaderWebController()
        controller.owner = self
        controller.loadViewIfNeeded()
        controller.loadDocument()
        return controller
    }
    func updateUIViewController(_ controller: ReaderWebController, context: Context) {
        let changed = controller.owner?.documentID != documentID
        let layoutChanged = controller.owner?.settings != settings
        controller.owner = self
        if changed { controller.reloadForDocumentChange() }
        else if layoutChanged { controller.captureAndReload() }
        if let command = command, command.id != controller.lastCommand {
            controller.lastCommand = command.id
            controller.perform(command.action)
        }
    }
    static func dismantleUIViewController(_ controller: ReaderWebController, coordinator: ()) { controller.tearDown() }
}

final class ReaderWebController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    var owner: ReaderWebView?
    var lastCommand: UUID?
    private(set) var webView: WKWebView!
    private var viewport = ReaderViewport()
    private var ready = false
    private var turning = false
    private var restoring = false
    private var settling = false
    private var pendingCapture = false
    private var documentGeneration = UUID()
    private var settleTimer: Timer?
    private var documentTimer: Timer?
    private var cardPan: UIPanGestureRecognizer!
    private var cardTranslation: CGFloat = 0
    private var cardDirection = 0
    private var cardActive = false
    private var scrollStartY: CGFloat = 0
    private var pendingBoundary: Int?
    private var lastSize: CGSize = .zero
    private var savedAnchor: ReaderViewport?
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { ReaderOrientationPolicy.mask }
    override var shouldAutorotate: Bool { true }

    override func loadView() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.allowsInlineMediaPlayback = false
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        let bridge = WeakReaderScriptHandler(self)
        configuration.userContentController.add(bridge, contentWorld: .defaultClient, name: "reader")
        configuration.userContentController.addUserScript(WKUserScript(source: Self.script, injectionTime: .atDocumentEnd, forMainFrameOnly: true, in: .defaultClient))
        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.scrollView.delegate = self
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.showsHorizontalScrollIndicator = false
        webView.scrollView.showsVerticalScrollIndicator = false
        webView.scrollView.bounces = true
        webView.scrollView.decelerationRate = .fast
        webView.isOpaque = false
        webView.allowsLinkPreview = false
        webView.accessibilityIdentifier = "reader.document"
        view = webView
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        tap.delegate = self
        tap.cancelsTouchesInView = false
        let long = UILongPressGestureRecognizer(target: self, action: #selector(longPressed(_:)))
        long.minimumPressDuration = 0.5
        long.delegate = self
        tap.require(toFail: long)
        webView.addGestureRecognizer(tap)
        webView.addGestureRecognizer(long)
        cardPan = UIPanGestureRecognizer(target: self, action: #selector(cardDragged(_:)))
        cardPan.maximumNumberOfTouches = 1
        cardPan.delegate = self
        webView.addGestureRecognizer(cardPan)
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let size = view.bounds.size
        guard size.width > 0, size.height > 0, size != lastSize else { return }
        let previous = lastSize
        lastSize = size
        if ready && previous != .zero { restore(anchor: viewport) }
    }
    func tearDown() {
        settleTimer?.invalidate()
        documentTimer?.invalidate()
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.scrollView.delegate = nil
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "reader", contentWorld: .defaultClient)
        webView.configuration.userContentController.removeAllUserScripts()
    }
    func reloadForDocumentChange() {
        savedAnchor = nil
        loadDocument()
    }
    func loadDocument() {
        guard let owner = owner else { return }
        documentGeneration = UUID()
        ready = false; turning = false; restoring = true; pendingCapture = false
        settling = false
        cardActive = false; cardTranslation = 0; cardDirection = 0; pendingBoundary = nil
        webView.isUserInteractionEnabled = true
        settleTimer?.invalidate()
        documentTimer?.invalidate()
        let generation = documentGeneration
        documentTimer = Timer.scheduledTimer(withTimeInterval: 24, repeats: false) { [weak self] _ in
            guard let self = self, self.documentGeneration == generation, !self.ready else { return }
            self.owner?.onError("章节排版超时，请重新加载正文与字体")
        }
        webView.stopLoading()
        webView.backgroundColor = UIColor(owner.settings.theme.background)
        webView.scrollView.backgroundColor = UIColor(owner.settings.theme.background)
        webView.scrollView.isPagingEnabled = owner.settings.mode == .paged
        webView.scrollView.isScrollEnabled = owner.settings.mode != .card
        webView.scrollView.alwaysBounceHorizontal = owner.settings.mode == .paged
        webView.scrollView.alwaysBounceVertical = owner.settings.mode == .scroll
        cardPan.isEnabled = owner.settings.mode == .card
        viewport = ReaderViewport(paragraph: owner.restoreParagraph, percent: owner.restorePercent)
        let document = owner.html.replacingOccurrences(of: "<body ", with: "<body data-token=\"\(documentGeneration.uuidString)\" ")
        webView.loadHTMLString(document, baseURL: URL(string: "https://reader.mixn.invalid/"))
    }
    func captureAndReload() {
        guard ready, !turning, !restoring else { savedAnchor = viewport; loadDocument(); return }
        let generation = documentGeneration
        evaluate("MixnReader.state()") { [weak self] value in
            guard let self = self, generation == self.documentGeneration else { return }
            self.savedAnchor = self.parse(value) ?? self.viewport
            self.loadDocument()
        }
    }
    private func evaluate(_ source: String, completion: ((Any?) -> Void)? = nil) {
        webView.evaluateJavaScript(source, in: nil, in: .defaultClient) { result in
            switch result { case .success(let value): completion?(value)
            case .failure: completion?(nil) }
        }
    }
    private func parse(_ value: Any?) -> ReaderViewport? {
        guard let value = value as? [String: Any], let paragraph = value["paragraph"] as? Int,
              let percent = value["percent"] as? Int, let page = value["page"] as? Int, let count = value["pageCount"] as? Int else { return nil }
        return ReaderViewport(paragraph: max(0, paragraph), percent: min(100, max(0, percent)), page: max(0, page), pageCount: max(1, count))
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame, let body = message.body as? [String: Any],
              body["token"] as? String == documentGeneration.uuidString, let kind = body["kind"] as? String else { return }
        switch kind {
        case "ready":
            guard let owner = owner else { return }
            ready = true
            documentTimer?.invalidate()
            let anchor = savedAnchor ?? ReaderViewport(paragraph: owner.restoreParagraph, percent: owner.restorePercent)
            savedAnchor = nil
            restore(anchor: anchor, end: owner.restoreAtEnd)
        case "error":
            documentTimer?.invalidate()
            ready = false; turning = false; restoring = false
            owner?.onError(body["message"] as? String ?? "章节排版失败，请重试")
        case "layout":
            if ready && !turning && !restoring { restore(anchor: viewport) }
        case "imageError": break // The document exposes an explicit retryable image error beside that illustration.
        default: break
        }
    }
    private func restore(anchor: ReaderViewport, end: Bool = false) {
        restoring = true; turning = true
        webView.isUserInteractionEnabled = false
        let generation = documentGeneration
        evaluate("MixnReader.restore(\(anchor.paragraph),\(anchor.percent),\(end ? "true" : "false"))") { [weak self] value in
            guard let self = self, self.documentGeneration == generation else { return }
            self.webView.isUserInteractionEnabled = true
            self.restoring = false; self.turning = false
            guard let state = self.parse(value) else { self.owner?.onError("无法恢复阅读位置，请重试"); return }
            self.viewport = state; self.owner?.onSettled(state)
            if self.pendingCapture { self.pendingCapture = false; self.reportSettled() }
        }
    }
    private func reportSettled() {
        guard pendingBoundary == nil else { return }
        guard ready, !turning, !restoring, !settling, !webView.scrollView.isDragging, !webView.scrollView.isDecelerating else { return }
        settling = true
        let generation = documentGeneration
        evaluate("MixnReader.state()") { [weak self] value in
            guard let self = self, self.documentGeneration == generation else { return }
            self.settling = false
            guard !self.turning, !self.restoring, !self.webView.scrollView.isDragging,
                  !self.webView.scrollView.isDecelerating, let state = self.parse(value) else { return }
            self.viewport = state
            self.owner?.onSettled(state)
        }
    }
    func perform(_ action: ReaderWebCommand.Action) {
        guard ready else { if case .capture = action { pendingCapture = true }; return }
        switch action {
        case .capture: reportSettled()
        case .seek(let fraction):
            guard !turning, !restoring, !settling else { return }
            turning = true
            let generation = documentGeneration
            evaluate("MixnReader.seek(\(min(1, max(0, fraction))))") { [weak self] value in
                guard let self = self, self.documentGeneration == generation else { return }; self.turning = false
                if let state = self.parse(value) { self.viewport = state; self.owner?.onSettled(state) }
            }
        case .move(let delta): requestTurn(delta)
        }
    }
    private func requestTurn(_ delta: Int) {
        guard let owner = owner, ready, !turning, !restoring, !settling, !webView.scrollView.isDragging, !webView.scrollView.isDecelerating else { return }
        let direction = delta < 0 ? -1 : 1
        if owner.settings.mode == .card { beginCard(direction: direction, translation: 0, commit: true); return }
        let scroll = webView.scrollView
        if owner.settings.mode == .scroll {
            let maximum = max(0, scroll.contentSize.height - scroll.bounds.height)
            let target = min(maximum, max(0, scroll.contentOffset.y + CGFloat(direction) * scroll.bounds.height * 0.9))
            if abs(target - scroll.contentOffset.y) < 1 { owner.onBoundary(direction); return }
            turning = true
            webView.isUserInteractionEnabled = false
            scroll.setContentOffset(CGPoint(x: 0, y: target), animated: true)
        } else {
            let target = viewport.page + direction
            if target < 0 || target >= viewport.pageCount { owner.onBoundary(direction); return }
            turning = true
            webView.isUserInteractionEnabled = false
            scroll.setContentOffset(CGPoint(x: CGFloat(target) * scroll.bounds.width, y: 0), animated: true)
        }
    }
    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) { turning = false; webView.isUserInteractionEnabled = true; finishScroll() }
    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) { settleTimer?.invalidate(); scrollStartY = scrollView.contentOffset.y }
    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if owner?.settings.mode == .paged {
            let width = scrollView.bounds.width
            let end = CGFloat(max(0, viewport.pageCount - 1)) * width
            if scrollView.contentOffset.x < -width * 0.2 { pendingBoundary = -1 }
            else if scrollView.contentOffset.x > end + width * 0.2 { pendingBoundary = 1 }
        } else if owner?.settings.mode == .scroll {
            let maximum = max(0, scrollView.contentSize.height - scrollView.bounds.height)
            if scrollStartY <= 1 && scrollView.contentOffset.y < -70 { pendingBoundary = -1 }
            else if scrollStartY >= maximum - 1 && scrollView.contentOffset.y > maximum + 70 { pendingBoundary = 1 }
        }
        if !decelerate { finishScroll() }
    }
    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { finishScroll() }
    private func finishScroll() {
        if let direction = pendingBoundary { pendingBoundary = nil; owner?.onBoundary(direction) }
        else { reportSettled() }
    }
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard ready, !turning, !restoring, owner?.settings.mode == .scroll else { return }
        settleTimer?.invalidate()
        settleTimer = Timer.scheduledTimer(withTimeInterval: 0.18, repeats: false) { [weak self] _ in self?.reportSettled() }
    }
    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer === cardPan {
            let velocity = cardPan.velocity(in: view)
            return ready && !turning && !restoring && !settling && abs(velocity.x) > abs(velocity.y)
        }
        return ready && !turning && !restoring && !settling
    }
    @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
        guard recognizer.state == .ended, let owner = owner, !turning else { return }
        let point = recognizer.location(in: webView)
        resolveImage(point) { [weak self] image in
            guard let self = self, !self.turning else { return }
            if let image = image { owner.onImage(image, false); return }
            let action = ReaderDocument.tapAction(zone: owner.settings.tapZone, x: Double(point.x / max(1, self.view.bounds.width)), y: Double(point.y / max(1, self.view.bounds.height)), inversion: owner.settings.tapInversion)
            switch action { case .controls: owner.onControls(); case .previous: self.requestTurn(-1); case .next: self.requestTurn(1) }
        }
    }
    @objc private func longPressed(_ recognizer: UILongPressGestureRecognizer) {
        guard recognizer.state == .began else { return }
        resolveImage(recognizer.location(in: webView)) { [weak self] image in if let image = image { self?.owner?.onImage(image, true) } }
    }
    private func resolveImage(_ point: CGPoint, completion: @escaping (URL?) -> Void) {
        evaluate("MixnReader.imageAt(\(point.x),\(point.y))") { value in completion((value as? String).flatMap(ReaderDocument.imageURL)) }
    }
    @objc private func cardDragged(_ pan: UIPanGestureRecognizer) {
        let translation = pan.translation(in: webView).x
        switch pan.state {
        case .began:
            cardActive = true; cardDirection = 0; cardTranslation = 0
        case .changed:
            guard cardActive, !turning else { return }
            let direction = translation < 0 ? 1 : -1
            cardTranslation = min(view.bounds.width, max(-view.bounds.width, translation))
            if cardDirection != direction {
                cardDirection = direction
                evaluate("MixnReader.cardStart(\(direction))")
            }
            evaluate("MixnReader.cardDrag(\(cardTranslation))")
        case .ended, .cancelled, .failed:
            guard cardActive else { return }; cardActive = false
            let direction = cardDirection == 0 ? (translation < 0 ? 1 : -1) : cardDirection
            let commit = ReaderDocument.shouldCommitDrag(translation: Double(translation), width: Double(view.bounds.width), cancelled: pan.state != .ended)
            beginCard(direction: direction, translation: cardTranslation, commit: commit)
        default: break
        }
    }
    private func beginCard(direction: Int, translation: CGFloat, commit: Bool) {
        guard !turning, ready else { return }
        turning = true
        webView.isUserInteractionEnabled = false
        let target = viewport.page + direction
        let isBoundary = target < 0 || target >= viewport.pageCount
        evaluate("MixnReader.cardFinish(\(direction),\(translation),\(commit ? "true" : "false"))")
        let generation = documentGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.21) { [weak self] in
            guard let self = self, self.documentGeneration == generation else { return }
            self.evaluate("MixnReader.cardCleanup()") { [weak self] value in
                guard let self = self, self.documentGeneration == generation else { return }
                self.webView.isUserInteractionEnabled = true
                self.turning = false; self.cardDirection = 0; self.cardTranslation = 0
                if commit && isBoundary { self.owner?.onBoundary(direction) }
                else if commit, let state = self.parse(value) { self.viewport = state; self.owner?.onSettled(state) }
            }
        }
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        // Generated about:blank is the only allowed top-level document; links, popups and redirects are never followed.
        let allowed = action.navigationType == .other && (action.request.url?.absoluteString == "about:blank" || action.request.url?.host == "reader.mixn.invalid") && action.targetFrame?.isMainFrame == true
        decisionHandler(allowed ? .allow : .cancel)
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code != NSURLErrorCancelled && (error as NSError).code != 102 { owner?.onError(error.localizedDescription) }
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code != NSURLErrorCancelled && (error as NSError).code != 102 { owner?.onError(error.localizedDescription) }
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { owner?.onError("阅读进程已退出，请重新加载章节") }

    private static let script = #"""
    (()=>{'use strict';
      const article=document.getElementById('content');let currentCard=null, pendingPage=null;
      const send=(kind,extra={})=>webkit.messageHandlers.reader.postMessage(Object.assign({kind,token:document.body.dataset.token},extra));
      const scrolling=()=>document.body.classList.contains('scroll');
      const width=()=>Math.max(1,window.innerWidth),height=()=>Math.max(1,window.innerHeight);
      const count=()=>scrolling()?1:Math.max(1,Math.ceil((article.scrollWidth+2*article.offsetLeft-1)/width()));
      function measure(){document.body.style.width=scrolling()?'100%':(count()*width())+'px';}
      const page=()=>Math.max(0,Math.min(count()-1,Math.round(window.scrollX/width())));
      const blocks=()=>Array.from(article.querySelectorAll('[data-block]'));
      function state(){
        const list=blocks(), w=width(),h=height(),p=page();let visible=0;
        for(let i=0;i<list.length;i++){const rects=Array.from(list[i].getClientRects());if(rects.some(r=>r.bottom>1&&r.top<h&&r.right>1&&r.left<w)){visible=i;break;}}
        const maxY=Math.max(0,document.documentElement.scrollHeight-h);
        const ratio=scrolling()?(maxY>1?window.scrollY/maxY:1):(count()>1?p/(count()-1):1);
        if(scrolling()&&ratio>=0.999&&list.length)visible=list.length-1;
        return {paragraph:visible,percent:Math.round(Math.max(0,Math.min(1,ratio))*100),page:p,pageCount:count()};
      }
      function restore(index,percent,end){
        cleanup();measure();const list=blocks(),i=Math.max(0,Math.min(list.length-1,index));
        if(end){window.scrollTo(scrolling()?0:(count()-1)*width(),scrolling()?document.documentElement.scrollHeight:0);return state();}
        if(list[i]){const rects=Array.from(list[i].getClientRects()),r=rects[0];if(scrolling())window.scrollTo(0,Math.max(0,r.top+window.scrollY-8));else{const first=Math.max(0,Math.floor((r.left+window.scrollX)/width())),last=Math.max(first,Math.floor((rects[rects.length-1].left+window.scrollX)/width())),saved=Math.round(Math.max(0,Math.min(100,percent))/100*(count()-1));window.scrollTo(Math.max(first,Math.min(last,saved))*width(),0);}}
        else seek(percent/100);return state();
      }
      function seek(fraction){cleanup();const value=Math.max(0,Math.min(1,fraction));if(scrolling())window.scrollTo(0,value*Math.max(0,document.documentElement.scrollHeight-height()));else window.scrollTo(Math.round(value*(count()-1))*width(),0);return state();}
      function layer(index){
        const root=document.createElement('div');Object.assign(root.style,{position:'fixed',left:'0',top:'0',width:'100vw',height:'100vh',overflow:'hidden',background:getComputedStyle(document.body).backgroundColor,zIndex:'50',pointerEvents:'none'});
        const clone=article.cloneNode(true);clone.querySelectorAll('[id]').forEach(n=>n.removeAttribute('id'));
        Object.assign(clone.style,{position:'absolute',left:(-index*width())+'px',top:'0',marginLeft:getComputedStyle(article).marginLeft,marginRight:getComputedStyle(article).marginRight,height:'100vh',width:getComputedStyle(article).width,columnWidth:getComputedStyle(article).columnWidth,columnGap:getComputedStyle(article).columnGap,columnFill:'auto'});
        root.appendChild(clone);document.body.appendChild(root);return root;
      }
      function cleanup(){if(currentCard){currentCard.under.remove();currentCard.over.remove();currentCard=null;}if(pendingPage!==null){window.scrollTo(pendingPage*width(),0);pendingPage=null;}return state();}
      function cardStart(direction){cleanup();const p=page(),target=p+direction;const safe=Math.max(0,Math.min(count()-1,target));
        const under=layer(direction>0?safe:p),over=layer(direction>0?p:safe);over.style.zIndex='51';over.style.boxShadow='3px 0 14px #0003';
        currentCard={under,over,direction,target,valid:target>=0&&target<count()};cardDrag(0);return true;
      }
      function cardDrag(x){if(!currentCard)return;const c=currentCard;c.over.style.transition='none';c.over.style.transform='translateX('+(c.direction>0?Math.min(0,x):Math.max(0,x)-width())+'px)';}
      function cardFinish(direction,x,commit){if(!currentCard||currentCard.direction!==direction)cardStart(direction);cardDrag(x);const c=currentCard;c.over.getBoundingClientRect();c.over.style.transition='transform 180ms ease-out';c.over.style.transform='translateX('+(direction>0?(commit?-width():0):(commit?0:-width()))+'px)';if(commit&&c.valid)pendingPage=c.target;return true;}
      window.MixnReader={state,restore,seek,imageAt:(x,y)=>{const n=document.elementFromPoint(x,y);return n&&n.tagName==='IMG'?n.currentSrc||n.src:null;},cardStart,cardDrag,cardFinish,cardCleanup:cleanup};
      const images=Array.from(article.querySelectorAll('img'));
      for(const image of images){image.addEventListener('error',()=>{image.style.minHeight='100px';image.alt='图片加载失败（轻点重试或长按查看）';send('imageError');});image.addEventListener('load',()=>{if(document.body.classList.contains('ready'))send('layout');});}
      const font=document.body.dataset.embedded==='yes'?document.fonts.load('21px MixnEmbedded',article.textContent.slice(0,200)):Promise.resolve([]);
      let timeout;Promise.race([font,new Promise((_,reject)=>{timeout=setTimeout(()=>reject(new Error('章节字体载入超时，请重试')),15000);})]).then(loaded=>{
        clearTimeout(timeout);if(document.body.dataset.embedded==='yes'&&(!loaded||!loaded.length))throw new Error('章节字体无法用于排版，请重试');
        return Promise.all(images.map(image=>image.complete?Promise.resolve():new Promise(resolve=>{image.addEventListener('load',resolve,{once:true});image.addEventListener('error',resolve,{once:true});setTimeout(resolve,5000);})));
      }).then(()=>{measure();document.body.classList.add('ready');requestAnimationFrame(()=>requestAnimationFrame(()=>send('ready')));}).catch(error=>send('error',{message:error.message}));
    })();
    """#
}

private final class WeakReaderScriptHandler: NSObject, WKScriptMessageHandler {
    weak var target: ReaderWebController?
    init(_ target: ReaderWebController) { self.target = target }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) { target?.userContentController(userContentController, didReceive: message) }
}
