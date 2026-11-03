                            }
                            type = "text/plain"
                        }, null))
                    }
                }
            }
            BasePlaybackService.OFFLINE_VIDEO -> {
                playbackService?.quality?.url?.let { videoUrl ->
                    val uri = if (videoUrl.endsWith(".m3u8")) {
                        videoUrl.substringBefore("%2F").toUri()
                    } else {
                        videoUrl.toUri()
                    }
                    startActivity(Intent.createChooser(Intent().apply {
                        action = Intent.ACTION_SEND
                        setDataAndType(uri, requireContext().contentResolver.getType(uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        playbackService?.title?.let {
                            putExtra(Intent.EXTRA_TITLE, it)
                        }
                    }, null))
                }
            }
        }
    }

    fun changePlayerMode() {
        with(binding) {
            if (canEnterPictureInPicture()) {
                if (!controllerHideOnTouch && !controllerIsAnimating && controllerAutoHide && !binding.playerControls.progressBar.isPressed) {
                    playerControls.root.postDelayed(controllerHideAction, 3000)
                }
                controllerHideOnTouch = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                    requireContext().prefs().getBoolean(C.PLAYER_PICTURE_IN_PICTURE, true)
                ) {
                    requireActivity().setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(true).build())
                }
            } else {
                controllerHideOnTouch = false
                showController(force = true)
                updateProgress()
                requireView().keepScreenOn = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                ) {
                    requireActivity().setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
                }
            }
        }
    }

    protected fun showController(show: Boolean = true, force: Boolean = false) {
        if (!controllerIsAnimating) {
            if (!binding.playerControls.root.isVisible) {
                binding.playerControls.root.removeCallbacks(controllerHideAction)
                if (show) {
                    controllerAnimation = binding.playerControls.root.animate().apply {
                        alpha(1f)
                        setDuration(250L)
                        setListener(
                            object : AnimatorListenerAdapter() {
                                override fun onAnimationStart(animation: Animator) {
                                    controllerIsAnimating = true
                                    if (view != null) {
                                        binding.playerControls.root.visibility = View.VISIBLE
                                    }
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    controllerIsAnimating = false
                                    setListener(null)
                                    if (view != null && controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                                        binding.playerControls.root.postDelayed(controllerHideAction, 3000)
                                    }
                                }
                            }
                        )
                        start()
                    }
                }
            } else {
                binding.playerControls.root.removeCallbacks(controllerHideAction)
                if (controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                    binding.playerControls.root.postDelayed(controllerHideAction, 3000)
                }
            }
        } else {
            if (force) {
                controllerAnimation?.cancel()
                binding.playerControls.root.removeCallbacks(controllerHideAction)
                binding.playerControls.root.alpha = 1f
                binding.playerControls.root.visibility = View.VISIBLE
                if (controllerAutoHide && controllerHideOnTouch && !binding.playerControls.progressBar.isPressed) {
                    binding.playerControls.root.postDelayed(controllerHideAction, 3000)
                }
            }
        }
    }

    private fun hideController(force: Boolean = false) {
        if (!controllerIsAnimating && binding.playerControls.root.isVisible) {
            controllerAnimation = binding.playerControls.root.animate().apply {
                alpha(0f)
                setDuration(250L)
                setListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) {
                            controllerIsAnimating = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            controllerIsAnimating = false
                            setListener(null)
                            if (view != null) {
                                binding.playerControls.root.visibility = View.GONE
                            }
                        }
                    }
                )
                start()
            }
        } else {
            if (force) {
                controllerAnimation?.cancel()
                binding.playerControls.root.alpha = 0f
                binding.playerControls.root.visibility = View.GONE
            }
        }
    }

    private fun showStatusBar() {
        if (isAdded) {
            WindowCompat.getInsetsController(
                requireActivity().window,
                requireActivity().window.decorView
            ).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hideStatusBar() {
        if (isAdded) {
            WindowCompat.getInsetsController(
                requireActivity().window,
                requireActivity().window.decorView
            ).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun enableBackground() {
        backgroundVisible = true
        binding.playerBackground.setBackgroundColor(
            if (isPortrait) {
                backgroundColor ?: MaterialColors.getColor(binding.playerBackground, com.google.android.material.R.attr.colorSurface).also { backgroundColor = it }
            } else {
                Color.BLACK
            }
        )
