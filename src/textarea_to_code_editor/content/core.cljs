(ns textarea-to-code-editor.content.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [>! chan alts!]]
            [domina.css :refer [sel]]
            [domina.events :refer [listen! current-target]]
            [domina :refer [insert-before! destroy! set-styles! add-class!]]
            [clj-di.core :refer [register!]]
            [textarea-to-code-editor.chrome.core :as c]))

(defn get-hover-chan
  "Returns chan in which we put hovering related events."
  []
  (let [ch (chan)]
    (doto (sel "textarea")
      (listen! :mouseenter #(go (>! ch [:enter (current-target %)])))
      (listen! :mouseleave #(go (>! ch [:leave]))))
    ch))

(defn to-code-editor
  "Converts textarea to code editor."
  [el hover-chan mode]
  (when el
    (let [id (str (gensym))
          width (.-scrollWidth el)
          height (.-scrollHeight el)
          content (.-value el)]
      (insert-before! el (str "<div id='" id "' style='
                                  width: " width "px;
                                  height: " height "px;
                                  font-size: 16px;'></div>"))
      (add-class! el id)
      (set-styles! el {:display "none"})
      (let [editor (.edit js/ace id)]
        (doto editor
          (.setTheme "ace/theme/monokai")
          (.setValue content)
          (.. getSession (setMode mode))
          (.. getSession (on "change" #(set! (.-value el)
                                             (.getValue editor))))))
      (doto (sel (str "#" id))
        (listen! :mouseenter #(go (>! hover-chan [:editor-enter (current-target %)])))
        (listen! :mouseleave #(go (>! hover-chan [:leave])))))))

(defn to-textarea
  "Converts code editor back to textarea."
  [el]
  (when el
    (set-styles! (sel (str "." (.-id el))) {:display "block"})
    (destroy! el)))

(defn update-editor-modes
  "Sends editor modes to backend."
  []
  (c/send-message* :update-modes (.. js/ace (require "ace/ext/modelist")
                                     -modesByName)))

(defn handle-messages!
  "Handle messages for background and events."
  [hover-chan msg-chan]
  (go-loop [active nil]
    (let [[[request data _] _] (alts! [hover-chan msg-chan])]
      (recur (condp = request
               :enter (do (update-editor-modes)
                          (c/send-message* :enter)
                          data)
               :leave (do (c/send-message* :leave) nil)
               :editor-enter (do (c/send-message* :editor-enter) data)
               :update-modes (update-editor-modes)
               :to-code-editor (do (to-code-editor active hover-chan data)
                                   (c/send-message* :leave)
                                   nil)
               :to-textarea (do (to-textarea active) nil))))))

(when (c/available?)
  (register! :chrome (c/real-chrome.))
  (handle-messages! (get-hover-chan) (c/get-messages-chan)))
