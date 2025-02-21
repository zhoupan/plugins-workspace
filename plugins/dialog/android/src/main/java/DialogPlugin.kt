// Copyright 2019-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

package app.tauri.dialog

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.activity.result.ActivityResult
import app.tauri.Logger
import app.tauri.annotation.ActivityCallback
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

@InvokeArg
class Filter {
  lateinit var extensions: Array<String>
}

@InvokeArg
class FilePickerOptions {
  lateinit var filters: Array<Filter>
  var multiple: Boolean? = null
}

@InvokeArg
class MessageOptions {
  var title: String? = null
  lateinit var message: String
  var okButtonLabel: String? = null
  var cancelButtonLabel: String? = null
}

@InvokeArg
class SaveFileDialogOptions {
  var fileName: String? = null
  lateinit var filters: Array<Filter>
}

@TauriPlugin
class DialogPlugin(private val activity: Activity): Plugin(activity) {
  var filePickerOptions: FilePickerOptions? = null

  @Command
  fun showFilePicker(invoke: Invoke) {
    try {
      val args = invoke.parseArgs(FilePickerOptions::class.java)
      val parsedTypes = parseFiltersOption(args.filters)
      
      val intent = if (parsedTypes.isNotEmpty()) {
        val intent = Intent(Intent.ACTION_PICK)
        setIntentMimeTypes(intent, parsedTypes)
        intent
      } else {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent
      }

      intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, args.multiple ?: false)
      
      startActivityForResult(invoke, intent, "filePickerResult")
    } catch (ex: Exception) {
      val message = ex.message ?: "Failed to pick file"
      Logger.error(message)
      invoke.reject(message)
    }
  }

  @ActivityCallback
  fun filePickerResult(invoke: Invoke, result: ActivityResult) {
    try {
      when (result.resultCode) {
        Activity.RESULT_OK -> {
          val callResult = createPickFilesResult(result.data)
          invoke.resolve(callResult)
        }
        Activity.RESULT_CANCELED -> invoke.reject("File picker cancelled")
        else -> invoke.reject("Failed to pick files")
      }
    } catch (ex: java.lang.Exception) {
      val message = ex.message ?: "Failed to read file pick result"
      Logger.error(message)
      invoke.reject(message)
    }
  }

  private fun createPickFilesResult(data: Intent?): JSObject {
    val callResult = JSObject()
    if (data == null) {
      callResult.put("files", null)
      return callResult
    }
    val uris: MutableList<String?> = ArrayList()
    if (data.clipData == null) {
      val uri: Uri? = data.data
      uris.add(uri?.toString())
    } else {
      for (i in 0 until data.clipData!!.itemCount) {
        val uri: Uri = data.clipData!!.getItemAt(i).uri
        uris.add(uri.toString())
      }
    }
    callResult.put("files", JSArray.from(uris.toTypedArray()))
    return callResult
  }
  
  private fun parseFiltersOption(filters: Array<Filter>): Array<String> {
    val mimeTypes = mutableListOf<String>()
    for (filter in filters) {
      for (ext in filter.extensions) {
        if (ext.contains('/')) {
          mimeTypes.add(if (ext == "text/csv") "text/comma-separated-values" else ext)
        } else {
          MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let {
            mimeTypes.add(it)
          }
        }
      }
    }
    return mimeTypes.toTypedArray()
  }

  private fun setIntentMimeTypes(intent: Intent, mimeTypes: Array<String>) {
    if (mimeTypes.isNotEmpty()) {
      var uniqueMimeKind = true
      var mimeKind: String? = null
      for (mime in mimeTypes) {
        val kind = mime.split("/")[0]
        if (mimeKind == null) {
          mimeKind = kind
        } else if (mimeKind != kind) {
          uniqueMimeKind = false
        }
      }

      if (uniqueMimeKind) {
        if (mimeTypes.size > 1) {
          intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
          intent.type = Intent.normalizeMimeType("$mimeKind/*")
        } else {
          intent.type = mimeTypes[0]
        }
      } else {
        intent.type = "*/*"
      }
    } else {
      intent.type = "*/*"
    }
  }
  
  @Command
  fun showMessageDialog(invoke: Invoke) {
    val args = invoke.parseArgs(MessageOptions::class.java)
    
    if (activity.isFinishing) {
      invoke.reject("App is finishing")
      return
    }

    val handler = { cancelled: Boolean, value: Boolean ->
      val ret = JSObject()
      ret.put("cancelled", cancelled)
      ret.put("value", value)
      invoke.resolve(ret)
    }

    Handler(Looper.getMainLooper())
      .post {
        val builder = AlertDialog.Builder(activity)
        
        if (args.title != null) {
          builder.setTitle(args.title)
        }
        builder
          .setMessage(args.message)
          .setPositiveButton(
            args.okButtonLabel ?: "OK"
          ) { dialog, _ ->
            dialog.dismiss()
            handler(false, true)
          }
          .setOnCancelListener { dialog ->
            dialog.dismiss()
            handler(true, false)
          }
        if (args.cancelButtonLabel != null) {
          builder.setNegativeButton( args.cancelButtonLabel) { dialog, _ ->
            dialog.dismiss()
            handler(false, false)
          }
        }
        val dialog = builder.create()
        dialog.show()
      }
  }

  @Command
  fun saveFileDialog(invoke: Invoke) {
    try {
      val args = invoke.parseArgs(SaveFileDialogOptions::class.java)
      val parsedTypes = parseFiltersOption(args.filters)

      val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
      setIntentMimeTypes(intent, parsedTypes)

      intent.addCategory(Intent.CATEGORY_OPENABLE)
      intent.putExtra(Intent.EXTRA_TITLE, args.fileName ?: "")
      startActivityForResult(invoke, intent, "saveFileDialogResult")
    } catch (ex: Exception) {
      val message = ex.message ?: "Failed to pick save file"
      Logger.error(message)
      invoke.reject(message)
    }
  }

  @ActivityCallback
  fun saveFileDialogResult(invoke: Invoke, result: ActivityResult) {
    try {
      when (result.resultCode) {
        Activity.RESULT_OK -> {
          val callResult = JSObject()
          val intent: Intent? = result.data
          if (intent != null) {
            val uri = intent.data
            if (uri != null) {
              callResult.put("file", uri.toString())
            }
          }
          invoke.resolve(callResult)
        }
        Activity.RESULT_CANCELED -> invoke.reject("File picker cancelled")
        else -> invoke.reject("Failed to pick files")
      }
    } catch (ex: java.lang.Exception) {
      val message = ex.message ?: "Failed to read file pick result"
      Logger.error(message)
      invoke.reject(message)
    }
  }
}